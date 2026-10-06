package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceKind;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

            // 单次快照：账单金额口径与 billedEventIds 来自同一读，
            // 快照后新到的迟到事件由其回调走重算/补差流程收敛
            List<UsageEvent> basis = usageService.aggregatedEvents(
                    subscriptionId, periodStart, periodEnd);
            long usedUnits = basis.stream().mapToLong(UsageEvent::quantity).sum();
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
            basis.forEach(e -> invoice.getBilledEventIds().add(e.eventId()));
            invoiceRepository.save(invoice);
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

    // ------------------------------------------------------------------
    // 迟到用量补报闭环
    // ------------------------------------------------------------------

    /** 迟到补报处理结果。 */
    public record LateUsageOutcome(Kind kind, Invoice invoice, long deltaCents, String detail) {
        public enum Kind {
            /** 账期尚未出账：补报只进入用量汇总，出账时自然被计入。 */
            NO_INVOICE_YET,
            /** 账单未结清（PENDING/DUNNING）：金额已按全部事件重算并更新。 */
            RECALCULATED,
            /** 账单已结清（PAID/FAILED 终态）：已生成补差调整单。 */
            ADJUSTMENT_CREATED,
            /** 无新增应收（无新迟到用量或差额为零）：不产生任何单据。 */
            NO_CHANGE
        }
    }

    /**
     * 迟到用量补报的统一入口（由 UsageService 在接受迟到事件后回调，也可显式触发）。
     *
     * <p>按账期账单状态分流（全程在订阅级锁内，与收款确认互斥）：
     * <ul>
     *   <li>未出账：NO_INVOICE_YET，不生成任何单据；</li>
     *   <li>PENDING/DUNNING：按当前全部事件重算用量费并更新账单总额，
     *       重算审计（差异来源事件集合）写入 {@link Invoice#getRecalcAudits()}；</li>
     *   <li>PAID/FAILED 终态：差额 = 当前口径应收 − 已出账（原账单 + 既有调整单），
     *       差额 &gt; 0 才生成 ADJUSTMENT 补差单；差额 ≤ 0 不生成（幂等：
     *       同一批补报重复/并发触发不会重复计费）。</li>
     * </ul>
     */
    public LateUsageOutcome processLateUsage(String subscriptionId, long periodStart,
                                             UsageEvent triggerEvent) {
        return invoiceRepository.withLock(subscriptionId, () -> {
            var existing = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, periodStart);
            if (existing.isEmpty()) {
                log.info("[billing] lateUsage sub={} period={} trigger={} decision=NO_INVOICE_YET "
                                + "(事件已入汇总，出账时自然计入)",
                        subscriptionId, periodStart, triggerEvent.eventId());
                return new LateUsageOutcome(LateUsageOutcome.Kind.NO_INVOICE_YET, null, 0L,
                        "账期未出账，补报仅进入用量汇总");
            }
            Invoice inv = existing.get();
            if (inv.getStatus() == InvoiceStatus.PENDING || inv.getStatus() == InvoiceStatus.DUNNING) {
                return recalculateOpenInvoice(inv, triggerEvent);
            }
            return createAdjustmentIfNeeded(inv, triggerEvent);
        });
    }

    /** 未结清账单重算：用量费按当前全部事件重算，套餐费保持不变（分段已冻结）。 */
    private LateUsageOutcome recalculateOpenInvoice(Invoice inv, UsageEvent trigger) {
        long includedUnits = inv.getLineItems().stream()
                .mapToLong(Invoice.LineItem::includedUnits).sum();
        // 单次快照：金额口径与依据事件集合来自同一读，保证审计记录自洽
        List<UsageEvent> snapshot = usageService.aggregatedEvents(
                inv.getSubscriptionId(), inv.getPeriodStart(), inv.getPeriodEnd());
        long usedUnits = snapshot.stream().mapToLong(UsageEvent::quantity).sum();
        long overageUnits = Math.max(0L, usedUnits - includedUnits);
        Plan billingPlan = planRepository.requireByCode(billingPlanCode(inv));
        long newUsageFee = pricingService.overageFee(billingPlan.overageTiers(), overageUnits);
        long newTotal = inv.getPlanFeeCents() + newUsageFee;
        if (newTotal == inv.getTotalCents()) {
            log.info("[billing] lateUsage recalc id={} decision=NO_CHANGE total={}c",
                    inv.getId(), inv.getTotalCents());
            return new LateUsageOutcome(LateUsageOutcome.Kind.NO_CHANGE, inv, 0L,
                    "重算金额与原金额一致");
        }
        List<String> basisEventIds = snapshot.stream().map(UsageEvent::eventId).toList();
        long oldUsageFee = inv.getUsageFeeCents();
        long oldTotal = inv.getTotalCents();
        inv.setUsageFeeCents(newUsageFee);
        inv.setTotalCents(newTotal);
        inv.getRecalcAudits().add(new Invoice.RecalcAudit(
                clock.nowMillis(), trigger.eventId(), oldUsageFee, newUsageFee,
                oldTotal, newTotal, basisEventIds));
        inv.getBilledEventIds().clear();
        inv.getBilledEventIds().addAll(basisEventIds);
        invoiceRepository.save(inv);
        log.info("[billing] lateUsage recalc id={} decision=RECALCULATED trigger={} "
                        + "usedUnits={} includedUnits={} overageUnits={} "
                        + "usageFee {}c -> {}c total {}c -> {}c basisEvents={}",
                inv.getId(), trigger.eventId(), usedUnits, includedUnits, overageUnits,
                oldUsageFee, newUsageFee, oldTotal, newTotal, basisEventIds);
        return new LateUsageOutcome(LateUsageOutcome.Kind.RECALCULATED, inv,
                newTotal - oldTotal, "账单未结清，已按全部事件重算");
    }

    /** 已结清账期补差：差额 = 当前口径应收 − 已出账总额，差额 &gt; 0 才落调整单。 */
    private LateUsageOutcome createAdjustmentIfNeeded(Invoice original, UsageEvent trigger) {
        String subId = original.getSubscriptionId();
        long periodStart = original.getPeriodStart();
        long includedUnits = original.getLineItems().stream()
                .mapToLong(Invoice.LineItem::includedUnits).sum();
        // 单次快照：事件列表与总量来自同一读，避免并发补报落在两次读之间
        // 造成“金额口径”与“来源明细”不自洽（快照后新到的事件由它自己的
        // 迟到回调再触发一轮补差，差额法自动收敛）。
        List<UsageEvent> snapshot = usageService.aggregatedEvents(
                subId, periodStart, original.getPeriodEnd());
        long usedUnits = snapshot.stream().mapToLong(UsageEvent::quantity).sum();
        long overageUnits = Math.max(0L, usedUnits - includedUnits);
        Plan billingPlan = planRepository.requireByCode(billingPlanCode(original));
        long expectedUsageFee = pricingService.overageFee(billingPlan.overageTiers(), overageUnits);
        long expectedTotal = original.getPlanFeeCents() + expectedUsageFee;

        List<Invoice> adjustments = invoiceRepository.findAdjustments(subId, periodStart);
        long billedTotal = original.getTotalCents()
                + adjustments.stream().mapToLong(Invoice::getTotalCents).sum();
        long delta = expectedTotal - billedTotal;
        if (delta <= 0L) {
            log.info("[billing] lateUsage adjust id={} decision=NO_CHANGE expected={}c "
                            + "billed={}c delta={}c (无新增应收，不生成补差单)",
                    original.getId(), expectedTotal, billedTotal, delta);
            return new LateUsageOutcome(LateUsageOutcome.Kind.NO_CHANGE, original, 0L,
                    "无新增应收差额，不生成补差调整单");
        }

        // 补差来源：快照内未被原账单（生成/重算时已计费事件）与既有调整单覆盖的事件
        Set<String> covered = new HashSet<>(original.getBilledEventIds());
        adjustments.forEach(a -> covered.addAll(a.getSourceEventIds()));
        List<UsageEvent> sourceEvents = snapshot.stream()
                .filter(e -> !covered.contains(e.eventId()))
                .toList();
        List<String> sourceEventIds = sourceEvents.stream().map(UsageEvent::eventId).toList();

        // 计价说明：新增用量先抵扣包含额度剩余部分，超出部分按计费套餐阶梯边际计价。
        // 已出账口径用量 = 当前口径用量 − 本次新增用量（覆盖集合互斥且并集为全部事件）。
        long newUnits = sourceEvents.stream().mapToLong(UsageEvent::quantity).sum();
        long usedUnitsBefore = usedUnits - newUnits;
        long overageBefore = Math.max(0L, usedUnitsBefore - includedUnits);
        List<PricingService.TierSlice> tierSlices = pricingService.overageTierSlices(
                billingPlan.overageTiers(), overageBefore, overageUnits);
        long sliceTotal = tierSlices.stream()
                .mapToLong(PricingService.TierSlice::feeCents).sum();
        Invoice.AdjustmentPricing pricing = new Invoice.AdjustmentPricing(
                billingPlan.code(), includedUnits, usedUnitsBefore, usedUnits,
                newUnits, overageBefore, overageUnits, tierSlices, delta);

        Invoice adj = new Invoice();
        adj.setId("ADJ-" + subId + "-" + periodStart + "-" + (adjustments.size() + 1));
        adj.setSubscriptionId(subId);
        adj.setPeriodStart(periodStart);
        adj.setPeriodEnd(original.getPeriodEnd());
        adj.setGeneratedAt(clock.nowMillis());
        adj.setKind(InvoiceKind.ADJUSTMENT);
        adj.setLinkedInvoiceId(original.getId());
        adj.setPlanFeeCents(0L);
        adj.setUsageFeeCents(delta);
        adj.setTotalCents(delta);
        adj.setStatus(InvoiceStatus.PENDING);
        adj.setAttempt(0);
        adj.setNextRetryAt(0L);
        adj.getSourceEventIds().addAll(sourceEventIds);
        sourceEvents.forEach(e -> adj.getSourceEventUsages().add(
                new Invoice.SourceEventUsage(e.eventId(), e.quantity(), e.occurredAt())));
        adj.setNewUnitsTotal(newUnits);
        adj.setAdjustmentPricing(pricing);
        invoiceRepository.save(adj);
        log.info("[billing] lateUsage adjust decision=ADJUSTMENT_CREATED id={} linkedTo={} "
                        + "trigger={} delta={}c (expected={}c billed={}c) sourceEvents={} "
                        + "newUnits={} usedUnits {}->{} overage {}->{} tierSlices={} "
                        + "sliceTotal={}c conserved(sliceTotal=delta)={}",
                adj.getId(), original.getId(), trigger.eventId(), delta,
                expectedTotal, billedTotal, sourceEventIds,
                newUnits, usedUnitsBefore, usedUnits, overageBefore, overageUnits,
                tierSlices, sliceTotal, sliceTotal == delta);
        return new LateUsageOutcome(LateUsageOutcome.Kind.ADJUSTMENT_CREATED, adj, delta,
                "账期已结清，生成补差调整单");
    }

    /** 账期计费口径：覆盖账期内最后时刻的套餐分段（与出账时的开口段一致）。 */
    private String billingPlanCode(Invoice inv) {
        Subscription s = subscriptionRepository.requireById(inv.getSubscriptionId());
        String code = null;
        for (PlanSegment seg : s.getSegments()) {
            if (seg.start() < inv.getPeriodEnd()) {
                code = seg.planCode();
            }
        }
        if (code == null) {
            throw reject(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "账期内不存在套餐分段: sub=" + inv.getSubscriptionId()
                            + " period=[" + inv.getPeriodStart() + "," + inv.getPeriodEnd() + ")");
        }
        return code;
    }

    /** 对账结果：原账单 + 补差调整单 与 当前口径应收总额的核对。 */
    public record Reconciliation(String subscriptionId, long periodStart,
                                 Invoice originalInvoice, List<Invoice> adjustments,
                                 long originalAmountCents, long adjustmentTotalCents,
                                 long expectedTotalCents, long discrepancyCents) {
    }

    /**
     * 对账：对任一订阅的任一账期，给出
     * “原账单金额 + 已产生补差金额 = 当前口径应收总额”的核对结果。
     * 已出账账期差额恒为 0 且可复现；未出账账期 discrepancy 即当前应收（尚未出账）。
     */
    public Reconciliation reconcile(String subscriptionId, long periodStart) {
        Subscription s = subscriptionRepository.requireById(subscriptionId);
        long periodEnd = SubscriptionService.nextPeriodEnd(periodStart);
        var existing = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, periodStart);
        List<Invoice> adjustments = invoiceRepository.findAdjustments(subscriptionId, periodStart);

        long planFee;
        long includedUnits;
        if (existing.isPresent()) {
            planFee = existing.get().getPlanFeeCents();
            includedUnits = existing.get().getLineItems().stream()
                    .mapToLong(Invoice.LineItem::includedUnits).sum();
        } else {
            planFee = 0L;
            includedUnits = 0L;
            for (PlanSegment seg : s.getSegments()) {
                long segEnd = seg.end() == null ? periodEnd : seg.end();
                long lo = Math.max(seg.start(), periodStart);
                long hi = Math.min(segEnd, periodEnd);
                if (hi <= lo) {
                    continue;
                }
                Plan plan = planRepository.requireByCode(seg.planCode());
                planFee += pricingService.proratedMonthlyPrice(
                        plan.monthlyPriceCents(), lo, hi, periodStart, periodEnd);
                includedUnits += pricingService.proratedIncludedUnits(
                        plan.includedUnits(), lo, hi, periodStart, periodEnd);
            }
        }
        long usedUnits = usageService.aggregate(subscriptionId, periodStart, periodEnd);
        long overageUnits = Math.max(0L, usedUnits - includedUnits);
        String planCode = null;
        for (PlanSegment seg : s.getSegments()) {
            if (seg.start() < periodEnd) {
                planCode = seg.planCode();
            }
        }
        long usageFee = planCode == null ? 0L
                : pricingService.overageFee(
                        planRepository.requireByCode(planCode).overageTiers(), overageUnits);
        long expectedTotal = planFee + usageFee;

        long originalAmount = existing.map(Invoice::getTotalCents).orElse(0L);
        long adjustmentTotal = adjustments.stream().mapToLong(Invoice::getTotalCents).sum();
        long discrepancy = expectedTotal - originalAmount - adjustmentTotal;
        log.info("[billing] reconcile sub={} period=[{},{}) original={}c adjustments={}c "
                        + "expected={}c (planFee={}c usageFee={}c usedUnits={} includedUnits={}) "
                        + "discrepancy={}c",
                subscriptionId, periodStart, periodEnd, originalAmount, adjustmentTotal,
                expectedTotal, planFee, usageFee, usedUnits, includedUnits, discrepancy);
        return new Reconciliation(subscriptionId, periodStart, existing.orElse(null),
                adjustments, originalAmount, adjustmentTotal, expectedTotal, discrepancy);
    }
}
