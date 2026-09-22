package com.github.highcumontoa.subscriptionbillingjava.web;

import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.PlanChange;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.model.UsageEvent;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.service.DunningService;
import com.github.highcumontoa.subscriptionbillingjava.service.SubscriptionService;
import com.github.highcumontoa.subscriptionbillingjava.service.UsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅用于本地手工验证的 HTTP 接口（全部处理在本地内存完成）。
 */
@RestController
@RequestMapping("/local")
public class BillingController {

    private final SubscriptionService subscriptionService;
    private final UsageService usageService;
    private final BillingService billingService;
    private final DunningService dunningService;
    private final TimeService timeService;

    public BillingController(SubscriptionService subscriptionService,
                             UsageService usageService,
                             BillingService billingService,
                             DunningService dunningService,
                             TimeService timeService) {
        this.subscriptionService = subscriptionService;
        this.usageService = usageService;
        this.billingService = billingService;
        this.dunningService = dunningService;
        this.timeService = timeService;
    }

    @PostMapping("/subscriptions")
    public Map<String, Object> create(@RequestBody Map<String, String> req) {
        return subView(subscriptionService.create(req.get("planCode")));
    }

    @PostMapping("/subscriptions/{id}/change")
    public Map<String, Object> change(@PathVariable String id, @RequestBody Map<String, Object> req) {
        Instant eff = req.get("effectiveAt") == null ? null : Instant.parse((String) req.get("effectiveAt"));
        Long expectedVersion = req.get("expectedVersion") == null ? null
                : Long.valueOf(String.valueOf(req.get("expectedVersion")));
        return changeView(subscriptionService.changePlan(id, (String) req.get("targetPlanCode"),
                eff, expectedVersion));
    }

    @PostMapping("/subscriptions/{id}/renew")
    public Map<String, Object> renew(@PathVariable String id) {
        return changeView(subscriptionService.renew(id));
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, String> req) {
        Instant eff = req == null || req.get("effectiveAt") == null ? null
                : Instant.parse(req.get("effectiveAt"));
        return subView(subscriptionService.cancel(id, eff));
    }

    @PostMapping("/usage")
    public Map<String, Object> usage(@RequestBody Map<String, Object> req) {
        Instant occ = req.get("occurredAt") == null ? null : Instant.parse((String) req.get("occurredAt"));
        UsageEvent e = usageService.report((String) req.get("eventId"),
                (String) req.get("subscriptionId"),
                Long.parseLong(String.valueOf(req.get("quantity"))), occ);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("eventId", e.eventId());
        v.put("subscriptionId", e.subscriptionId());
        v.put("quantity", e.quantity());
        v.put("occurredAt", e.occurredAt());
        v.put("receivedAt", e.receivedAt());
        return v;
    }

    @PostMapping("/subscriptions/{id}/bills")
    public Map<String, Object> generate(@PathVariable String id,
                                        @RequestParam(defaultValue = "0") long periodIndex) {
        return billView(billingService.generateBill(id, periodIndex));
    }

    @PostMapping("/bills/{billId}/confirm")
    public Map<String, Object> confirm(@PathVariable String billId,
                                       @RequestBody Map<String, String> req) {
        return billView(billingService.confirmAndCollect(billId, req.get("idempotencyKey")));
    }

    @PostMapping("/bills/{billId}/retry")
    public Map<String, Object> retry(@PathVariable String billId) {
        return billView(billingService.retryPayment(billId));
    }

    @PostMapping("/dunning/run")
    public List<Map<String, Object>> dunning() {
        return dunningService.runRetryCycle().stream().map(this::billView).toList();
    }

    /** 本地演示：把固定时钟推进到指定时刻（如账期到期/退避到期）。 */
    @PostMapping("/time/travel")
    public Map<String, String> travel(@RequestBody Map<String, String> req) {
        timeService.setClock(Clock.fixed(Instant.parse(req.get("at")), java.time.ZoneOffset.UTC));
        return Map.of("now", timeService.now().toString());
    }

    @PostMapping("/time/reset")
    public Map<String, String> resetTime() {
        timeService.resetClock();
        return Map.of("now", timeService.now().toString());
    }

    private Map<String, Object> subView(Subscription s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", s.getId());
        v.put("planCode", s.getPlanCode());
        v.put("status", s.getStatus());
        v.put("anchor", s.getAnchor());
        v.put("currentPeriodStart", s.getCurrentPeriodStart());
        v.put("currentPeriodEnd", s.getCurrentPeriodEnd());
        v.put("version", s.getVersion());
        return v;
    }

    private Map<String, Object> changeView(PlanChange c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.id());
        v.put("subscriptionId", c.subscriptionId());
        v.put("changeType", c.changeType());
        v.put("fromPlanCode", c.fromPlanCode());
        v.put("toPlanCode", c.toPlanCode());
        v.put("effectiveAt", c.effectiveAt());
        v.put("proratedDeltaCents", c.proratedDeltaCents());
        v.put("baseVersion", c.baseVersion());
        v.put("note", c.note());
        return v;
    }

    private Map<String, Object> billView(Bill b) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", b.getId());
        v.put("subscriptionId", b.getSubscriptionId());
        v.put("periodStart", b.getPeriodStart());
        v.put("periodEnd", b.getPeriodEnd());
        v.put("status", b.getStatus());
        v.put("totalCents", b.getTotalCents());
        v.put("lineItems", b.getLineItems());
        v.put("attempts", b.getAttempts());
        v.put("nextRetryAt", b.getNextRetryAt());
        v.put("dunningNote", b.getDunningNote());
        return v;
    }
}
