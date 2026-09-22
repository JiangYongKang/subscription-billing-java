package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.billing.LocalPaymentGateway;
import com.github.highcumontoa.subscriptionbillingjava.billing.RetryPolicy;
import com.github.highcumontoa.subscriptionbillingjava.domain.Plan;
import com.github.highcumontoa.subscriptionbillingjava.domain.PlanSegment;
import com.github.highcumontoa.subscriptionbillingjava.domain.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.repo.InvoiceRepository;
import com.github.highcumontoa.subscriptionbillingjava.repo.PlanRepository;
import com.github.highcumontoa.subscriptionbillingjava.repo.SubscriptionRepository;
import com.github.highcumontoa.subscriptionbillingjava.usage.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 账单生成与收款确认服务。
 *
 * <p>账单合并口径（一个账期一张账单，金额生成后冻结）：
 * <ol>
 *   <li>套餐费用 = 账期内每个套餐分段的比例月费之和（分段长/账期长 × 月费，HALF_UP）；</li>
 *   <li>包含额度 = 各分段比例包含额度之和；超用 = max(0, 账期内去重总用量 − 包含额度)；</li>
 *   <li>用量费用 = 超用量按<b>开口段套餐</b>的阶梯累进计价（账期结束时生效的套餐）；</li>
 *   <li>totalCents = planFeeCents + usageFeeCents（守恒：落盘后任何操作不得改写金额）。</li>
 * </ol>
 *
 * <p>收款与幂等：
 * <ul>
 *   <li>confirmPayment 对 PAID 账单直接原样返回，不调用网关（重复确认零副作用）；</li>
 *   <li>FAILED 终态账单拒绝再次确认（INVOICE_NOT_CONFIRMABLE）；</li>
 *   <li>实际扣款仅在订阅级锁内、状态为 PENDING/DUNNING 时发生一次；</li>
 *   <li>扣款失败按 {@link RetryPolicy} 进入催收：attempt+1，未耗尽置 DUNNING 并调度
 *       nextRetryAt = now + intervals[attempt-1]，耗尽置 FAILED。</li>
 * </ul>
 */
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final UsageService usageService;
    private final PricingService pricingService;
    private final LocalPaymentGateway gateway;
    private final ClockSource clock;

    private RetryPolicy retryPolicy = new RetryPolicy(
            List.of(Duration.ofDays(1).toMillis(), Duration.ofDays(3).toMillis(),
                    Duration.ofDays(7).toMillis()));

    public BillingService(InvoiceRepository invoiceRepository,
                          SubscriptionRepository subscriptionRepository,
                          PlanRepository planRepository,
                          UsageService usageService,
                          PricingService pricingService,
                          LocalPaymentGateway gateway,
                          ClockSource clock) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.usageService = usageService;
        this.pricingService = pricingService;
        this.gateway = gateway;
        this.clock = clock;
    }

    public void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public Invoice generateInvoice(String subscriptionId, long periodStart) {
        Subscription s = subscriptionRepository.requireById(subscriptionId);
        return invoiceRepository.withLock(subscriptionId, () -> {
            var existing = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, periodStart);
            if (existing.isPresent()) {
                Invoice inv = existing.get();
                if (inv.getStatus() == InvoiceStatus.PAID) {
                    throw reject(ErrorCode.INVOICE_PERIOD_CONFLICT,
                            "该账期账单已支付，禁止重新生成（迟到用量请按调整账期补报）: id="
                                    + inv.getId());
                }
                log.info("[billing] generate reused existing PENDING/DUNNING invoice id={}",
                        inv.getId());
                return inv;
            }
            long periodEnd = periodStart == s.getPeriodStart()
                    ? s.getPeriodEnd()
                    : SubscriptionService.nextPeriodEnd(periodStart);
            long now = clock.nowMillis();
            if (now < periodEnd) {
                throw reject(ErrorCode.INVOICE_PERIOD_CONFLICT,
                        "账期尚未结束不能出账: periodStart=" + periodStart
                                + " periodEnd=" + periodEnd + " now=" + now);
            }

            long planFee = 0L;
            long includedUnits = 0L;
            java.util.List<Invoice.LineItem> lineItems = new java.util.ArrayList<>();
            for (PlanSegment seg : s.getSegments()) {
                long segEnd = seg.end() == null ? periodEnd : seg.end();
                long lo = Math.max(seg.start(), periodStart);
                long hi = Math.min(segEnd, periodEnd);
                if (hi <= lo) {
                    continue;
                }
                Plan plan = planRepository.requireByCode(seg.planCode());
                long segFee = pricingService.proratedMonthlyPrice(
                        plan.monthlyPriceCents(), lo, hi, periodStart, periodEnd);
                long segUnits = pricingService.proratedIncludedUnits(
                        plan.includedUnits(), lo, hi, periodStart, periodEnd);
                planFee += segFee;
                includedUnits += segUnits;
                lineItems.add(new Invoice.LineItem(plan.code(), lo, hi, segFee, segUnits));
            }

            long usedUnits = usageService.aggregate(subscriptionId, periodStart, periodEnd);
            long overageUnits = Math.max(0L, usedUnits - includedUnits);
            String billingPlanCode = s.getSegments().get(s.getSegments().size() - 1).planCode();
            Plan billingPlan = planRepository.requireByCode(billingPlanCode);
            long usageFee = pricingService.overageFee(billingPlan.overageTiers(), overageUnits);
            long total = planFee + usageFee;

            Invoice invoice = new Invoice();
            invoice.setId("INV-" + subscriptionId + "-" + periodStart);
            invoice.setSubscriptionId(subscriptionId);
            invoice.setPeriodStart(periodStart);
            invoice.setPeriodEnd(periodEnd);
            invoice.setGeneratedAt(now);
            invoice.setPlanFeeCents(planFee);
            invoice.setUsageFeeCents(usageFee);
            invoice.setTotalCents(total);
            invoice.setStatus(InvoiceStatus.PENDING);
            invoice.setAttempt(0);
            invoice.setNextRetryAt(0L);
            invoice.getLineItems().addAll(lineItems);
            invoiceRepository.save(invoice);

            List<UsageEvent> basis = usageService.aggregatedEvents(
                    subscriptionId, periodStart, periodEnd);
            log.info("[billing] generate decision=CREATED id={} sub={} period=[{},{}) "
                            + "planFee={}c includedUnits={} usedUnits={} overageUnits={} "
                            + "usageFee={}c total={}c basisEvents={} conserved(total=plan+usage)={}",
                    invoice.getId(), subscriptionId, periodStart, periodEnd,
                    planFee, includedUnits, usedUnits, overageUnits, usageFee, total,
                    basis.size(), total == planFee + usageFee);
            return invoice;
        });
    }

    public Invoice getInvoice(String invoiceId) {
        return invoiceRepository.requireById(invoiceId);
    }

    public Invoice confirmPayment(String invoiceId) {
        Invoice invoice = invoiceRepository.requireById(invoiceId);
        return invoiceRepository.withLock(invoice.getSubscriptionId(), () -> {
            if (invoice.getStatus() == InvoiceStatus.PAID) {
                // 幂等：重复确认原样返回，绝不发起第二次扣款
                log.info("[billing] confirm idempotent PAID id={} total={}c decision=NOOP "
                                + "(no gateway call)", invoice.getId(), invoice.getTotalCents());
                return invoice;
            }
            if (invoice.getStatus() == InvoiceStatus.FAILED) {
                throw reject(ErrorCode.INVOICE_NOT_CONFIRMABLE,
                        "账单已处于催收耗尽终态 FAILED，不能再次确认: id=" + invoice.getId());
            }
            long now = clock.nowMillis();
            int attempt = invoice.getAttempt() + 1;
            invoice.setAttempt(attempt);
            var result = gateway.charge(invoice, attempt);
            invoice.getAttemptAt().add(now);
            invoice.getAttemptResults().add(
                    (result.success ? "SUCCESS" : "DECLINED") + ":" + result.reason
                            + (result.reference != null ? ":" + result.reference : ""));

            if (result.success) {
                invoice.setStatus(InvoiceStatus.PAID);
                log.info("[billing] confirm decision=PAID id={} attempt={} total={}c ref={}",
                        invoice.getId(), attempt, invoice.getTotalCents(), result.reference);
            } else {
                applyFailure(invoice, attempt, now, result.reason);
            }
            invoiceRepository.save(invoice);
            return invoice;
        });
    }

    /**
     * 失败处理（BillingService 首跳与 DunningService 重试共用）：
     * 未达上限 -> DUNNING + nextRetryAt；已达上限 -> FAILED。
     */
    void applyFailure(Invoice invoice, int attempt, long now, String reason) {
        if (attempt >= retryPolicy.maxAttempts()) {
            invoice.setStatus(InvoiceStatus.FAILED);
            invoice.setNextRetryAt(0L);
            log.warn("[billing] payment decision=FAILED_EXHAUSTED id={} attempts={}/{} reason={}",
                    invoice.getId(), attempt, retryPolicy.maxAttempts(), reason);
        } else {
            long wait = retryPolicy.intervalsMillis().get(attempt - 1);
            invoice.setStatus(InvoiceStatus.DUNNING);
            invoice.setNextRetryAt(now + wait);
            log.warn("[billing] payment decision=DUNNING id={} attempts={}/{} nextRetryAt={} "
                            + "(now+{}ms) reason={}",
                    invoice.getId(), attempt, retryPolicy.maxAttempts(),
                    invoice.getNextRetryAt(), wait, reason);
        }
    }

    private BillingException reject(ErrorCode code, String message) {
        log.warn("[billing] rejected code={} detail={}", code, message);
        return new BillingException(code, message);
    }
}
