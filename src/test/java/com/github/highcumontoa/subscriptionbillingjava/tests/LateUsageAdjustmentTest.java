package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceKind;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.usage.UsageEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 迟到用量补报闭环：结清后补差调整单、未结清重算、幂等与对账。
 * 每个用例打印输入、关键状态与判定依据（[case] 前缀）。
 */
class LateUsageAdjustmentTest {

    private BillingTestFixtures fx() {
        return BillingTestFixtures.create();
    }

    /** BASIC 用 250 -> 超 150 -> 100*5+50*3=650，total=1650。 */
    private Invoice paidBasicInvoice(BillingTestFixtures fx, String subId, long periodStart) {
        Invoice inv = fx.billingService.generateInvoice(subId, periodStart);
        fx.billingService.confirmPayment(inv.getId());
        return inv;
    }

    /**
     * 补差明细守恒不变量：Σ逐笔用量 = 新增总用量；
     * Σ逐笔费用 = Σ计价切片费用 = 调整单总额；来源事件与逐笔明细一一对应。
     */
    private void assertAdjustmentDetailConsistent(Invoice adj) {
        var detail = adj.getAdjustmentDetail();
        long lineUnits = detail.lines().stream()
                .mapToLong(Invoice.AdjustmentLine::quantity).sum();
        long lineFees = detail.lines().stream()
                .mapToLong(Invoice.AdjustmentLine::feeCents).sum();
        long sliceUnits = detail.pricingSlices().stream()
                .mapToLong(Invoice.PricingSlice::units).sum();
        long sliceFees = detail.pricingSlices().stream()
                .mapToLong(Invoice.PricingSlice::feeCents).sum();
        System.out.println("[case] detail invariant id=" + adj.getId()
                + " lineUnits=" + lineUnits + "/" + detail.addedUnits()
                + " sliceUnits=" + sliceUnits
                + " lineFees=" + lineFees + "c sliceFees=" + sliceFees
                + "c total=" + adj.getTotalCents() + "c");
        assertEquals(detail.addedUnits(), lineUnits, "逐笔用量之和必须等于新增总用量");
        assertEquals(detail.overageAfterUnits() - detail.overageBeforeUnits(),
                sliceUnits, "计价切片覆盖单位数必须等于新增超用量");
        assertEquals(adj.getTotalCents(), lineFees, "逐笔费用之和必须等于补差总额");
        assertEquals(adj.getTotalCents(), sliceFees, "计价切片费用之和必须等于补差总额");
        assertEquals(adj.getSourceEventIds(),
                detail.lines().stream().map(Invoice.AdjustmentLine::eventId).toList(),
                "逐笔明细必须与来源补报记录一一对应");
    }

    @Test
    @DisplayName("结清后补报: 生成补差调整单、来源可核对、可收款、对账差额为零")
    void lateReportAfterPaid_createsAdjustment_payableAndReconciled() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 250L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = paidBasicInvoice(fx, sub.getId(), p0);
        System.out.println("[case] setup PAID invoice id=" + inv.getId()
                + " total=" + inv.getTotalCents() + "c status=" + inv.getStatus());
        assertEquals(InvoiceStatus.PAID, inv.getStatus());
        assertEquals(1650L, inv.getTotalCents());

        // 结清后迟到补报 100 单位（发生在已结清账期内）
        fx.usageService.report("EVT-LATE-1", sub.getId(), 100L, p0 + fx.days(10));

        List<Invoice> adjs = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        System.out.println("[case] after late report adjustments=" + adjs.stream()
                .map(a -> a.getId() + ":" + a.getTotalCents() + "c src=" + a.getSourceEventIds())
                .toList());
        assertEquals(1, adjs.size(), "结清后补报必须形成一张补差调整单");
        Invoice adj = adjs.get(0);
        assertEquals(InvoiceKind.ADJUSTMENT, adj.getKind());
        assertEquals(inv.getId(), adj.getLinkedInvoiceId(), "调整单必须关联原账单");
        assertEquals(List.of("EVT-LATE-1"), adj.getSourceEventIds(), "来源补报事件可核对");
        // 新口径：用量350 超250 -> 100*5+150*3=950，应收 1950；已出账 1650 -> 补差 300
        assertEquals(0L, adj.getPlanFeeCents());
        assertEquals(300L, adj.getUsageFeeCents());
        assertEquals(300L, adj.getTotalCents());
        assertEquals(InvoiceStatus.PENDING, adj.getStatus());

        // 核对明细：新增用量、计价规则切片、逐笔来源费用都可直接读出
        var detail = adj.getAdjustmentDetail();
        System.out.println("[case] adjustment detail plan=" + detail.planCode()
                + " addedUnits=" + detail.addedUnits()
                + " overage " + detail.overageBeforeUnits() + "->" + detail.overageAfterUnits()
                + " slices=" + detail.pricingSlices() + " lines=" + detail.lines());
        assertEquals("BASIC", detail.planCode(), "计价规则必须标明所用套餐");
        assertEquals(100L, detail.addedUnits(), "本次补报新增用量总数");
        assertEquals(150L, detail.overageBeforeUnits(), "补报前超用量(250-100)");
        assertEquals(250L, detail.overageAfterUnits(), "补报后超用量(350-100)");
        // 新增超用量 150->250 全部落在 BASIC 第二档(3分/个): 100*3=300
        assertEquals(List.of(new Invoice.PricingSlice(150L, 250L, 100L, 3L, 300L)),
                detail.pricingSlices(), "计价切片必须解释新增超用量按什么单价计费");
        assertEquals(1, detail.lines().size());
        var line = detail.lines().get(0);
        assertEquals("EVT-LATE-1", line.eventId());
        assertEquals(100L, line.quantity());
        assertEquals(p0 + fx.days(10), line.occurredAt());
        assertEquals(300L, line.feeCents(), "逐笔费用: 超用150->250 边际计价 100*3");
        assertAdjustmentDetailConsistent(adj);

        // 调整单像普通账单一样收款；重复确认零副作用
        fx.billingService.confirmPayment(adj.getId());
        fx.billingService.confirmPayment(adj.getId());
        System.out.println("[case] adjustment paid status=" + adj.getStatus()
                + " gatewayCharges=" + fx.gateway.recordsOf(adj.getId()).size());
        assertEquals(InvoiceStatus.PAID, adj.getStatus());
        assertEquals(1, fx.gateway.recordsOf(adj.getId()).size(), "调整单只扣款一次");
        assertEquals(300L, fx.gateway.recordsOf(adj.getId()).get(0).amountCents);

        // 对账：原账单 + 补差 = 当前口径应收，差额为零且可复现
        var rec1 = fx.billingService.reconcile(sub.getId(), p0);
        var rec2 = fx.billingService.reconcile(sub.getId(), p0);
        System.out.println("[case] reconcile original=" + rec1.originalAmountCents()
                + "c adjustments=" + rec1.adjustmentTotalCents()
                + "c expected=" + rec1.expectedTotalCents()
                + "c discrepancy=" + rec1.discrepancyCents() + "c");
        assertEquals(1650L, rec1.originalAmountCents());
        assertEquals(300L, rec1.adjustmentTotalCents());
        assertEquals(1950L, rec1.expectedTotalCents());
        assertEquals(0L, rec1.discrepancyCents(), "原账单+补差 必须等于当前口径应收");
        assertEquals(rec1.expectedTotalCents(), rec2.expectedTotalCents(), "对账可复现");
    }

    @Test
    @DisplayName("补差明细: 多笔补报合并一单、跨阶梯计价切片与逐笔费用可核对")
    void adjustmentDetail_multiEvent_tierCrossing_reconcilable() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        // 账期内只用 50 单位（额度内），账单 1000 并结清
        fx.usageService.report("EVT-1", sub.getId(), 50L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), p0);
        fx.billingService.confirmPayment(inv.getId());
        System.out.println("[case] setup PAID invoice total=" + inv.getTotalCents()
                + "c (usage 50 <= included 100)");
        assertEquals(1000L, inv.getTotalCents());

        // 两笔迟到补报先只入汇总（暂时摘掉自动回调），再显式触发一次处理，
        // 使一张调整单覆盖多笔来源记录
        fx.usageService.setLateUsageHandler(null);
        fx.usageService.report("EVT-X1", sub.getId(), 100L, p0 + fx.days(3));
        fx.usageService.report("EVT-X2", sub.getId(), 100L, p0 + fx.days(7));
        fx.usageService.setLateUsageHandler(fx.billingService::processLateUsage);
        var outcome = fx.billingService.processLateUsage(sub.getId(), p0,
                fx.eventRepository.findByEventId("EVT-X2"));
        System.out.println("[case] explicit trigger decision=" + outcome.kind()
                + " delta=" + outcome.deltaCents() + "c");
        assertEquals(BillingService.LateUsageOutcome.Kind.ADJUSTMENT_CREATED, outcome.kind());

        List<Invoice> adjs = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        assertEquals(1, adjs.size(), "两笔未覆盖补报应合并为一张补差调整单");
        Invoice adj = adjs.get(0);
        var detail = adj.getAdjustmentDetail();
        System.out.println("[case] detail plan=" + detail.planCode()
                + " addedUnits=" + detail.addedUnits()
                + " overage " + detail.overageBeforeUnits() + "->" + detail.overageAfterUnits()
                + " slices=" + detail.pricingSlices() + " lines=" + detail.lines());
        // 用量 50+200=250，超用 0->150；新增 200 中 50 被剩余额度吸收，150 计费
        assertEquals(200L, detail.addedUnits());
        assertEquals(0L, detail.overageBeforeUnits());
        assertEquals(150L, detail.overageAfterUnits());
        // 跨阶梯切片: (0,100]@5=500 + (100,150]@3=150 = 650
        assertEquals(List.of(
                        new Invoice.PricingSlice(0L, 100L, 100L, 5L, 500L),
                        new Invoice.PricingSlice(100L, 150L, 50L, 3L, 150L)),
                detail.pricingSlices(), "新增超用量必须按阶梯逐段展开计价规则");
        // 逐笔边际计价（按 occurredAt 排序）:
        // X1: 累计用量 50->150, 超用 0->50 -> 50*5=250（其中 50 被额度吸收不计费）
        // X2: 累计用量 150->250, 超用 50->150 -> 650-250=400
        assertEquals(2, detail.lines().size());
        assertEquals(new Invoice.AdjustmentLine("EVT-X1", p0 + fx.days(3), 100L, 250L),
                detail.lines().get(0));
        assertEquals(new Invoice.AdjustmentLine("EVT-X2", p0 + fx.days(7), 100L, 400L),
                detail.lines().get(1));
        assertEquals(650L, adj.getTotalCents());
        assertAdjustmentDetailConsistent(adj);

        // 对账差额为零；调整单可正常收款
        fx.billingService.confirmPayment(adj.getId());
        assertEquals(InvoiceStatus.PAID, adj.getStatus());
        var rec = fx.billingService.reconcile(sub.getId(), p0);
        System.out.println("[case] reconcile expected=" + rec.expectedTotalCents()
                + "c discrepancy=" + rec.discrepancyCents() + "c");
        assertEquals(0L, rec.discrepancyCents());
    }

    @Test
    @DisplayName("幂等与并发: 重复触发与并发补报不重复生成补差、不重复收款")
    void lateReport_idempotentAndConcurrent_noDoubleBilling() throws Exception {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 250L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = paidBasicInvoice(fx, sub.getId(), p0);

        // 同一事件重复触发处理（模拟重试）：第一次生成补差，之后全部 NO_CHANGE
        fx.usageService.report("EVT-LATE-1", sub.getId(), 100L, p0 + fx.days(10));
        UsageEvent trigger = fx.eventRepository.findByEventId("EVT-LATE-1");
        var again = fx.billingService.processLateUsage(sub.getId(), p0, trigger);
        var third = fx.billingService.processLateUsage(sub.getId(), p0, trigger);
        System.out.println("[case] repeated trigger decisions=" + again.kind() + "," + third.kind()
                + " adjustments=" + fx.invoiceRepository.findAdjustments(sub.getId(), p0).size());
        assertEquals(BillingService.LateUsageOutcome.Kind.NO_CHANGE, again.kind());
        assertEquals(BillingService.LateUsageOutcome.Kind.NO_CHANGE, third.kind());
        assertEquals(1, fx.invoiceRepository.findAdjustments(sub.getId(), p0).size(),
                "重复触发不得重复生成补差单");

        // 并发补报两个新迟到事件 + 并发重复触发：补差总额必须恰好等于应收差额
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reported = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (n < 2) {
                        // 两个真实补报：100 + 50 单位
                        fx.usageService.report("EVT-LATE-C" + n, sub.getId(),
                                n == 0 ? 100L : 50L, p0 + fx.days(12) + n);
                        reported.incrementAndGet();
                    } else {
                        // 其余线程并发重复触发同一账期的处理
                        fx.billingService.processLateUsage(sub.getId(), p0, trigger);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(2, reported.get());

        // 用量 250+100+100+50=500 超 400 -> 100*5+300*3=1400，应收 2400，已出账 1650 -> 补差合计 750
        List<Invoice> adjs = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        long adjTotal = adjs.stream().mapToLong(Invoice::getTotalCents).sum();
        System.out.println("[case] concurrent adjustments=" + adjs.stream()
                .map(a -> a.getId() + ":" + a.getTotalCents() + "c src=" + a.getSourceEventIds())
                .toList() + " adjTotal=" + adjTotal + "c");
        assertEquals(750L, adjTotal, "并发/重复触发下补差总额必须恰好等于应收差额");
        // 每张调整单的来源事件不重叠，且并集恰好是全部迟到补报
        long srcCount = adjs.stream().flatMap(a -> a.getSourceEventIds().stream()).distinct().count();
        assertEquals(3L, srcCount, "补差来源事件为 EVT-LATE-1/C0/C1 且不重复");
        // 每张调整单的明细都满足守恒不变量（逐笔费用=切片费用=单据总额）
        adjs.forEach(this::assertAdjustmentDetailConsistent);

        // 并发收款确认：每张调整单只扣款一次
        ExecutorService payPool = Executors.newFixedThreadPool(threads);
        for (Invoice a : adjs) {
            for (int i = 0; i < 3; i++) {
                payPool.submit(() -> fx.billingService.confirmPayment(a.getId()));
            }
        }
        payPool.shutdown();
        assertTrue(payPool.awaitTermination(30, TimeUnit.SECONDS));
        for (Invoice a : adjs) {
            assertEquals(InvoiceStatus.PAID, a.getStatus());
            assertEquals(1, fx.gateway.recordsOf(a.getId()).size(),
                    "并发确认下调整单只扣款一次: " + a.getId());
        }
        var rec = fx.billingService.reconcile(sub.getId(), p0);
        System.out.println("[case] final reconcile expected=" + rec.expectedTotalCents()
                + "c billed=" + (rec.originalAmountCents() + rec.adjustmentTotalCents())
                + "c discrepancy=" + rec.discrepancyCents() + "c");
        assertEquals(2400L, rec.expectedTotalCents());
        assertEquals(0L, rec.discrepancyCents());
    }

    @Test
    @DisplayName("未结清账期补报: 账单金额重算，与按事件重新汇总完全一致且差异可解释")
    void lateReportBeforeSettled_recalculatesInvoice_consistentWithAggregation() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 250L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), p0);
        System.out.println("[case] setup PENDING invoice total=" + inv.getTotalCents() + "c");
        assertEquals(1650L, inv.getTotalCents());
        assertEquals(InvoiceStatus.PENDING, inv.getStatus());

        // 账单未结清时迟到补报 100 单位 -> 必须反映到账单最终应收
        fx.usageService.report("EVT-LATE-1", sub.getId(), 100L, p0 + fx.days(10));

        // 判定依据：按事件重新汇总的独立重算
        long usedUnits = fx.usageService.aggregate(sub.getId(), p0, sub.getPeriodEnd());
        long expectedUsageFee = fx.pricingService.overageFee(
                fx.planRepository.requireByCode("BASIC").overageTiers(), usedUnits - 100L);
        long expectedTotal = 1000L + expectedUsageFee;
        System.out.println("[case] recalculated invoice total=" + inv.getTotalCents()
                + "c usageFee=" + inv.getUsageFeeCents() + "c | independent re-aggregation"
                + " usedUnits=" + usedUnits + " expectedTotal=" + expectedTotal + "c");
        assertEquals(350L, usedUnits);
        assertEquals(1950L, expectedTotal);
        assertEquals(expectedTotal, inv.getTotalCents(), "重算金额必须与按事件重新汇总一致");
        assertEquals(950L, inv.getUsageFeeCents());

        // 差异可解释：重算审计记录触发事件与完整依据事件集合
        assertEquals(1, inv.getRecalcAudits().size());
        var audit = inv.getRecalcAudits().get(0);
        System.out.println("[case] recalc audit trigger=" + audit.triggerEventId()
                + " total " + audit.oldTotalCents() + "c -> " + audit.newTotalCents()
                + "c basis=" + audit.basisEventIds());
        assertEquals("EVT-LATE-1", audit.triggerEventId());
        assertEquals(1650L, audit.oldTotalCents());
        assertEquals(1950L, audit.newTotalCents());
        assertEquals(List.of("EVT-1", "EVT-LATE-1"), audit.basisEventIds());

        // 无新增补报时再触发：金额不再变化
        var noop = fx.billingService.processLateUsage(sub.getId(), p0,
                fx.eventRepository.findByEventId("EVT-LATE-1"));
        assertEquals(BillingService.LateUsageOutcome.Kind.NO_CHANGE, noop.kind());
        assertEquals(1950L, inv.getTotalCents());

        // 重算后的账单按新金额收款，只扣一次；对账差额为零
        fx.billingService.confirmPayment(inv.getId());
        assertEquals(1950L, fx.gateway.recordsOf(inv.getId()).get(0).amountCents);
        assertEquals(0L, fx.billingService.reconcile(sub.getId(), p0).discrepancyCents());
    }

    @Test
    @DisplayName("边界: 调整单进入催收后，新补报只产生新单，已计入金额与明细不被改写")
    void adjustmentInDunning_notRewrittenByLaterReports() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 250L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = paidBasicInvoice(fx, sub.getId(), p0);

        // 第一笔补报 -> ADJ-1 = 300；编排首次扣款失败使其进入催收
        fx.usageService.report("EVT-LATE-1", sub.getId(), 100L, p0 + fx.days(10));
        Invoice adj1 = fx.invoiceRepository.findAdjustments(sub.getId(), p0).get(0);
        assertEquals(300L, adj1.getTotalCents());
        var detailSnapshot = adj1.getAdjustmentDetail();
        fx.gateway.prime(adj1.getId(), false, "CARD_DECLINED");
        fx.gateway.prime(adj1.getId(), true, "OK_RETRY");
        fx.billingService.confirmPayment(adj1.getId());
        System.out.println("[case] adjustment in dunning id=" + adj1.getId()
                + " status=" + adj1.getStatus() + " total=" + adj1.getTotalCents() + "c");
        assertEquals(InvoiceStatus.DUNNING, adj1.getStatus());

        // 催收期间又有新补报：只生成第二张调整单，ADJ-1 金额与明细不被改写
        fx.usageService.report("EVT-LATE-2", sub.getId(), 50L, p0 + fx.days(12));
        List<Invoice> adjs = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        System.out.println("[case] adjustments=" + adjs.stream()
                .map(a -> a.getId() + ":" + a.getStatus() + ":" + a.getTotalCents() + "c")
                .toList());
        assertEquals(2, adjs.size());
        // 用量 400 超 300 -> 100*5+200*3=1100，应收 2100；已出账 1650+300 -> 补差 150
        assertEquals(150L, adjs.get(1).getTotalCents());
        assertEquals(300L, adj1.getTotalCents(), "催收中的调整单金额不得被改写");
        assertEquals(InvoiceStatus.DUNNING, adj1.getStatus());
        assertEquals(detailSnapshot, adj1.getAdjustmentDetail(), "已计入明细不得被改写");
        assertAdjustmentDetailConsistent(adjs.get(1));

        // 到期重试成功 -> PAID；此后重复确认零副作用；对账差额为零
        fx.clock.set(adj1.getNextRetryAt() + 1L);
        fx.dunningService.processRetry(adj1.getId());
        fx.billingService.confirmPayment(adj1.getId());
        assertEquals(InvoiceStatus.PAID, adj1.getStatus());
        assertEquals(2, fx.gateway.recordsOf(adj1.getId()).size(),
                "ADJ-1 共两次扣款尝试（首次失败+重试成功），无额外扣款");
        var rec = fx.billingService.reconcile(sub.getId(), p0);
        System.out.println("[case] reconcile expected=" + rec.expectedTotalCents()
                + "c discrepancy=" + rec.discrepancyCents() + "c");
        assertEquals(2100L, rec.expectedTotalCents());
        assertEquals(0L, rec.discrepancyCents());
    }

    @Test
    @DisplayName("边界: 无新增不生成补差、未出账账期不生成、超窗补报明确拒绝")
    void lateReport_boundaries() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 250L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = paidBasicInvoice(fx, sub.getId(), p0);

        // 1) 没有新增迟到用量：显式触发也不生成补差调整单
        var noop = fx.billingService.processLateUsage(sub.getId(), p0,
                fx.eventRepository.findByEventId("EVT-1"));
        System.out.println("[case] no-new-usage decision=" + noop.kind()
                + " adjustments=" + fx.invoiceRepository.findAdjustments(sub.getId(), p0).size());
        assertEquals(BillingService.LateUsageOutcome.Kind.NO_CHANGE, noop.kind());
        assertTrue(fx.invoiceRepository.findAdjustments(sub.getId(), p0).isEmpty(),
                "无新增迟到用量不得凭空生成补差调整单");

        // 2) 账期未出账：补报只进汇总，出账时自然计入
        var sub2 = fx.subscriptionService.create("PRO", fx.clock.nowMillis());
        long q0 = sub2.getPeriodStart();
        fx.clock.set(sub2.getPeriodEnd() + 1L);
        fx.usageService.report("EVT-LATE-2", sub2.getId(), 600L, q0 + fx.days(3));
        System.out.println("[case] uninvoiced period adjustments="
                + fx.invoiceRepository.findAdjustments(sub2.getId(), q0).size());
        assertTrue(fx.invoiceRepository.findAdjustments(sub2.getId(), q0).isEmpty());
        Invoice inv2 = fx.billingService.generateInvoice(sub2.getId(), q0);
        // PRO 用 600 超 100 -> 100*4=400，total=3000+400=3400
        System.out.println("[case] uninvoiced period invoice total=" + inv2.getTotalCents() + "c");
        assertEquals(3400L, inv2.getTotalCents(), "未出账账期的补报在出账时必须被计入");

        // 3) 超出可受理范围的补报：明确拒绝且原因可区分
        BillingException tooOld = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-OLD", sub.getId(), 10L,
                        sub.getCreatedAt() - 1L));
        System.out.println("[case] reject tooOld code=" + tooOld.getCode());
        assertEquals(ErrorCode.EVENT_TOO_OLD, tooOld.getCode());

        BillingException outOfWindow = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-FUTURE", sub.getId(), 10L,
                        fx.clock.nowMillis() + fx.days(61)));
        System.out.println("[case] reject outOfWindow code=" + outOfWindow.getCode());
        assertEquals(ErrorCode.EVENT_OUT_OF_ORDER_WINDOW, outOfWindow.getCode());
    }
}
