package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceKind;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.billing.LocalPaymentGateway;
import com.github.highcumontoa.subscriptionbillingjava.billing.ReconciliationStatement;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 账单生成、收款确认与迟到补报处理服务。
 *
 * <p>账单合并口径（一个账期一张 REGULAR 账单）：
 * <ol>
 *   <li>套餐费用 = 账期内每个套餐分段的比例月费之和（分段长/账期长 × 月费，HALF_UP）；</li>
 *   <li>包含额度 = 各分段比例包含额度之和；超用 = max(0, 账期内去重总用量 − 包含额度)；</li>
 *   <li>用量费用 = 超用量按<b>账期末生效套餐</b>的阶梯累进计价；</li>
 *   <li>totalCents = planFeeCents + usageFeeCents。</li>
 * </ol>
 *
 * <p>迟到补报的两条路径（{@link #processLateUsage}，由用量上报接受后自动触发）：
 * <ul>
 *   <li><b>未结清</b>（PENDING/DUNNING/FAILED）：按当前已存事件整体重算账期金额并更新账单，
 *       重算审计（前后金额、新增用量、引发事件）记入 recalculations；金额不变则零副作用；</li>
 *   <li><b>已结清</b>（PAID）：原账单金额冻结，按“重算总额 − (原账单 + 已有补差)”生成
 *       ADJUSTMENT 补差调整单，记录本次新增用量、计价依据事件并关联原账单；
 *       无新增迟到事件或差额为零时不生成（幂等，不凭空出单）。</li>
 * </ul>
 *
 * <p>收款与幂等：
 * <ul>
 *   <li>confirmPayment 对 PAID 账单直接原样返回，不调用网关（重复确认零副作用）；</li>
 *   <li>FAILED 终态账单拒绝再次确认（INVOICE_NOT_CONFIRMABLE）；</li>
 *   <li>实际扣款仅在订阅级锁内、状态为 PENDING/DUNNING 时发生一次；</li>
 *   <li>扣款失败按 {@link RetryPolicy} 进入催收：attempt+1，未耗尽置 DUNNING 并调度
 *       nextRetryAt = now + intervals[attempt-1]，耗尽置 FAILED。</li>
 * </ul>
 *
 * <p>对账（{@link #reconcile}）：任一已出账账期满足
 * “原账单金额 + 已产生补差金额 = 按当前事件重算的应收总额”，差额为零且可复现。
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

    /** 一个账期的完整计价结果（纯函数口径：只依赖分段与已存事件，可复现）。 */
    private record Charges(long planFeeCents, long includedUnits, long usedUnits,
                           long overageUnits, long usageFeeCents, long totalCents,
                           List<Invoice.LineItem> lineItems, List<UsageEvent> basisEvents) {
    }

    /**
     * 按当前已存事件与套餐分段计算账期 [periodStart, periodEnd) 的全部费用。
     * 出账、未结清重算、补差与对账共用同一口径，保证结果完全一致。
     */
    private Charges computeCharges(Subscription s, long periodStart, long periodEnd) {
        long planFee = 0L;
        long includedUnits = 0L;
        List<Invoice.LineItem> lineItems = new ArrayList<>();
        String billingPlanCode = null;
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
            // 账期内最后一个相交分段即“账期末生效套餐”，其阶梯用于超用计价
            billingPlanCode = seg.planCode();
        }
        if (billingPlanCode == null) {
            throw reject(ErrorCode.INVOICE_PERIOD_CONFLICT,
                    "账期不被任何套餐分段覆盖，无法计价: sub=" + s.getId()
                            + " period=[" + periodStart + "," + periodEnd + ")");
        }
        List<UsageEvent> basis = usageService.aggregatedEvents(
                s.getId(), periodStart, periodEnd);
        long usedUnits = basis.stream().mapToLong(UsageEvent::quantity).sum();
        long overageUnits = Math.max(0L, usedUnits - includedUnits);
        Plan billingPlan = planRepository.requireByCode(billingPlanCode);
        long usageFee = pricingService.overageFee(billingPlan.overageTiers(), overageUnits);
        return new Charges(planFee, includedUnits, usedUnits, overageUnits, usageFee,
                planFee + usageFee, lineItems, basis);
    }

    public Invoice generateInvoice(String subscriptionId, long periodStart) {
        Subscription s = subscriptionRepository.requireById(subscriptionId);
        return invoiceRepository.withLock(subscriptionId, () -> {
            var existing = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, periodStart);
            if (existing.isPresent()) {
                Invoice inv = existing.get();
                if (inv.getStatus() == InvoiceStatus.PAID) {
                    throw reject(ErrorCode.INVOICE_PERIOD_CONFLICT,
                            "该账期账单已支付，禁止重新生成（迟到用量由补差调整单承载）: id="
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

            Charges c = computeCharges(s, periodStart, periodEnd);
            Invoice invoice = new Invoice();
            invoice.setId("INV-" + subscriptionId + "-" + periodStart);
            invoice.setSubscriptionId(subscriptionId);
            invoice.setKind(InvoiceKind.REGULAR);
            invoice.setPeriodStart(periodStart);
            invoice.setPeriodEnd(periodEnd);
            invoice.setGeneratedAt(now);
            invoice.setPlanFeeCents(c.planFeeCents());
            invoice.setUsageFeeCents(c.usageFeeCents());
            invoice.setTotalCents(c.totalCents());
            invoice.setStatus(InvoiceStatus.PENDING);
            invoice.setAttempt(0);
            invoice.setNextRetryAt(0L);
            invoice.getLineItems().addAll(c.lineItems());
            invoice.getBasisEventIds().addAll(
                    c.basisEvents().stream().map(UsageEvent::eventId).toList());
            invoiceRepository.save(invoice);

            log.info("[billing] generate decision=CREATED id={} sub={} period=[{},{}) "
                            + "planFee={}c includedUnits={} usedUnits={} overageUnits={} "
                            + "usageFee={}c total={}c basisEvents={} conserved(total=plan+usage)={}",
                    invoice.getId(), subscriptionId, periodStart, periodEnd,
                    c.planFeeCents(), c.includedUnits(), c.usedUnits(), c.overageUnits(),
                    c.usageFeeCents(), c.totalCents(),
                    c.basisEvents().size(), c.totalCents() == c.planFeeCents() + c.usageFeeCents());
            return invoice;
        });
    }

    public Invoice getInvoice(String invoiceId) {
        return invoiceRepository.requireById(invoiceId);
    }

    /**
     * 迟到补报的账单侧处理：由 UsageService 在事件被接受后自动触发，也可显式调用（幂等）。
     *
     * <p>定位 occurredAt 所属账期后按账单状态分流：未结清 -> 重算金额；
     * 已结清 -> 生成补差调整单；账期尚未出账 -> 无需处理（出账时自然纳入）。
     *
     * @return 新生成的补差调整单；未结清重算返回被更新的账单；无需处理返回 null
     */
    public Invoice processLateUsage(String subscriptionId, long occurredAt) {
        Subscription s = subscriptionRepository.requireById(subscriptionId);
        long periodMillis = SubscriptionService.nextPeriodEnd(s.getCreatedAt()) - s.getCreatedAt();
        if (occurredAt < s.getCreatedAt()) {
            log.info("[billing] lateUsage occurredAt={} < createdAt={} decision=NOOP",
                    occurredAt, s.getCreatedAt());
            return null;
        }
        long periodIndex = (occurredAt - s.getCreatedAt()) / periodMillis;
        long periodStart = s.getCreatedAt() + periodIndex * periodMillis;
        long periodEnd = periodStart + periodMillis;
        return invoiceRepository.withLock(subscriptionId, () -> {
            var existing = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, periodStart);
            if (existing.isEmpty()) {
                log.info("[billing] lateUsage sub={} period=[{},{}) decision=NOOP "
                                + "(no invoice yet; 出账时将按当前事件口径计入)",
                        subscriptionId, periodStart, periodEnd);
                return null;
            }
            Invoice invoice = existing.get();
            if (invoice.getStatus() == InvoiceStatus.PAID) {
                return createAdjustmentIfNeeded(s, invoice, periodStart, periodEnd);
            }
            return recalculateIfNeeded(s, invoice, periodStart, periodEnd);
        });
    }

    /**
     * 未结清账期：按当前已存事件整体重算并更新账单金额。
     * 金额不变 -> 零副作用；变化 -> 更新三段金额与计价依据，并追加重算审计。
     */
    private Invoice recalculateIfNeeded(Subscription s, Invoice invoice,
                                        long periodStart, long periodEnd) {
        Charges c = computeCharges(s, periodStart, periodEnd);
        if (c.totalCents() == invoice.getTotalCents()) {
            log.info("[billing] lateUsage recalc id={} status={} decision=NOOP "
                            + "(recomputed total={}c == frozen total)",
                    invoice.getId(), invoice.getStatus(), c.totalCents());
            return invoice;
        }
        Set<String> known = new HashSet<>(invoice.getBasisEventIds());
        List<UsageEvent> cause = c.basisEvents().stream()
                .filter(e -> !known.contains(e.eventId()))
                .toList();
        long deltaUnits = cause.stream().mapToLong(UsageEvent::quantity).sum();
        long now = clock.nowMillis();
        invoice.getRecalculations().add(new Invoice.Recalculation(
                now, invoice.getTotalCents(), c.totalCents(), deltaUnits,
                cause.stream().map(UsageEvent::eventId).toList()));
        invoice.setPlanFeeCents(c.planFeeCents());
        invoice.setUsageFeeCents(c.usageFeeCents());
        invoice.setTotalCents(c.totalCents());
        invoice.getLineItems().clear();
        invoice.getLineItems().addAll(c.lineItems());
        invoice.getBasisEventIds().clear();
        invoice.getBasisEventIds().addAll(
                c.basisEvents().stream().map(UsageEvent::eventId).toList());
        invoiceRepository.save(invoice);
        log.info("[billing] lateUsage recalc id={} status={} decision=RECALCULATED "
                        + "oldTotal={}c newTotal={}c (planFee={}c usageFee={}c) "
                        + "deltaUnits={} causeEvents={} usedUnits={} overageUnits={}",
                invoice.getId(), invoice.getStatus(),
                invoice.getRecalculations().get(invoice.getRecalculations().size() - 1)
                        .oldTotalCents(),
                c.totalCents(), c.planFeeCents(), c.usageFeeCents(),
                deltaUnits, invoice.getRecalculations().get(invoice.getRecalculations().size() - 1)
                        .causeEventIds(),
                c.usedUnits(), c.overageUnits());
        return invoice;
    }

    /**
     * 已结清账期：原账单冻结，按“重算总额 − (原账单 + 已有补差)”生成补差调整单。
     * 无新增迟到事件或差额为零 -> 不生成（幂等）；同一批补报重复/并发触发只出一张。
     */
    private Invoice createAdjustmentIfNeeded(Subscription s, Invoice original,
                                             long periodStart, long periodEnd) {
        List<Invoice> adjustments = invoiceRepository.findAdjustments(
                s.getId(), periodStart);
        Charges c = computeCharges(s, periodStart, periodEnd);

        Set<String> billed = new HashSet<>(original.getBasisEventIds());
        for (Invoice adj : adjustments) {
            billed.addAll(adj.getBasisEventIds());
        }
        List<UsageEvent> newEvents = c.basisEvents().stream()
                .filter(e -> !billed.contains(e.eventId()))
                .toList();
        if (newEvents.isEmpty()) {
            log.info("[billing] lateUsage adjustment original={} decision=NOOP "
                            + "(无新增迟到事件, existingAdjustments={})",
                    original.getId(), adjustments.size());
            return null;
        }
        long billedTotal = original.getTotalCents()
                + adjustments.stream().mapToLong(Invoice::getTotalCents).sum();
        long delta = c.totalCents() - billedTotal;
        long deltaUnits = newEvents.stream().mapToLong(UsageEvent::quantity).sum();
        if (delta <= 0L) {
            log.info("[billing] lateUsage adjustment original={} decision=NOOP "
                            + "(新增{}单位仍在已计费口径内, recomputed={}c billed={}c)",
                    original.getId(), deltaUnits, c.totalCents(), billedTotal);
            return null;
        }
        long billedPlanFee = original.getPlanFeeCents()
                + adjustments.stream().mapToLong(Invoice::getPlanFeeCents).sum();
        long billedUsageFee = original.getUsageFeeCents()
                + adjustments.stream().mapToLong(Invoice::getUsageFeeCents).sum();

        Invoice adj = new Invoice();
        adj.setId("ADJ-" + s.getId() + "-" + periodStart + "-" + (adjustments.size() + 1));
        adj.setSubscriptionId(s.getId());
        adj.setKind(InvoiceKind.ADJUSTMENT);
        adj.setLinkedInvoiceId(original.getId());
        adj.setPeriodStart(periodStart);
        adj.setPeriodEnd(periodEnd);
        adj.setGeneratedAt(clock.nowMillis());
        adj.setPlanFeeCents(c.planFeeCents() - billedPlanFee);
        adj.setUsageFeeCents(c.usageFeeCents() - billedUsageFee);
        adj.setTotalCents(delta);
        adj.setDeltaUnits(deltaUnits);
        adj.setStatus(InvoiceStatus.PENDING);
        adj.setAttempt(0);
        adj.setNextRetryAt(0L);
        adj.getBasisEventIds().addAll(
                newEvents.stream().map(UsageEvent::eventId).toList());
        invoiceRepository.save(adj);
        log.info("[billing] lateUsage adjustment decision=CREATED id={} linkedTo={} "
                        + "period=[{},{}) deltaUnits={} basisEvents={} "
                        + "rule: recomputedTotal={}c (planFee={}c + usageFee={}c, "
                        + "usedUnits={} overageUnits={}) - billed={}c -> delta={}c",
                adj.getId(), original.getId(), periodStart, periodEnd, deltaUnits,
                adj.getBasisEventIds(), c.totalCents(), c.planFeeCents(), c.usageFeeCents(),
                c.usedUnits(), c.overageUnits(), billedTotal, delta);
        return adj;
    }

    /**
     * 对账：任一已出账账期给出
     * “原账单金额 + 已产生补差金额 = 按当前事件重算的应收总额”，差额必须为零。
     * 只读口径与出账/重算/补差完全同源（{@link #computeCharges}），结果可复现。
     */
    public ReconciliationStatement reconcile(String subscriptionId, long periodStart) {
        Subscription s = subscriptionRepository.requireById(subscriptionId);
        return invoiceRepository.withLock(subscriptionId, () -> {
            Invoice original = invoiceRepository
                    .findBySubscriptionAndPeriod(subscriptionId, periodStart)
                    .orElseThrow(() -> reject(ErrorCode.INVOICE_NOT_FOUND,
                            "该账期没有原账单，无法对账: sub=" + subscriptionId
                                    + " periodStart=" + periodStart));
            long periodEnd = original.getPeriodEnd();
            List<Invoice> adjustments = invoiceRepository.findAdjustments(
                    subscriptionId, periodStart);
            Charges c = computeCharges(s, periodStart, periodEnd);
            long adjustmentTotal = adjustments.stream()
                    .mapToLong(Invoice::getTotalCents).sum();
            long expected = original.getTotalCents() + adjustmentTotal;
            long discrepancy = c.totalCents() - expected;
            ReconciliationStatement stmt = new ReconciliationStatement(
                    subscriptionId, periodStart, periodEnd,
                    original.getId(), original.getTotalCents(),
                    adjustments.stream().map(Invoice::getId).toList(),
                    adjustmentTotal, c.totalCents(), expected, discrepancy,
                    c.basisEvents().size());
            log.info("[billing] reconcile sub={} period=[{},{}) original={}={}c "
                            + "adjustments={} sum={}c recomputed={}c expected={}c "
                            + "discrepancy={}c balanced={}",
                    subscriptionId, periodStart, periodEnd,
                    original.getId(), original.getTotalCents(),
                    stmt.adjustmentInvoiceIds(), adjustmentTotal,
                    c.totalCents(), expected, discrepancy, stmt.balanced());
            return stmt;
        });
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
