package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceKind;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.billing.ReconciliationStatement;
import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账期结清后补报（补差调整单）与未结清补报（金额重算）的闭环测试。
 * 每个用例打印输入、关键状态与判定依据（[case] 前缀）。
 */
class LateUsageSettlementTest {

    private BillingTestFixtures fx() {
        return BillingTestFixtures.create();
    }

    /**
     * 结清后补报：BASIC 用量250 -> 账单1650 并 PAID；
     * 迟到补报 +50 -> 重算口径 total=1800 -> 补差调整单 150（50单位落在第二档3分/个）；
     * 再补报 +100 -> 第二张补差 300；两张补差均可收款；对账差额为零。
     */
    @Test
    @DisplayName("结清后补报: 生成与原账单关联的补差调整单，可收款，对账差额为零")
    void settledLateReport_createsAdjustment_andPayable() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 250L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), p0);
        fx.billingService.confirmPayment(inv.getId());
        System.out.println("[case] input BASIC used=250 state original=" + inv.getId()
                + " total=" + inv.getTotalCents() + "c status=" + inv.getStatus());
        assertEquals(InvoiceStatus.PAID, inv.getStatus());
        assertEquals(1650L, inv.getTotalCents());

        // 结清后迟到补报 +50（发生在本账期内）：上报接受后自动触发补差
        fx.usageService.report("EVT-LATE-1", sub.getId(), 50L, p0 + fx.days(10));
        List<Invoice> adjs = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        System.out.println("[case] input late +50 state adjustments=" + adjs.size());
        assertEquals(1, adjs.size(), "结清后补报必须形成一张补差调整单");
        Invoice adj = adjs.get(0);
        System.out.println("[case] adjustment id=" + adj.getId() + " kind=" + adj.getKind()
                + " linkedTo=" + adj.getLinkedInvoiceId() + " deltaUnits=" + adj.getDeltaUnits()
                + " basisEvents=" + adj.getBasisEventIds()
                + " usageFee=" + adj.getUsageFeeCents() + "c total=" + adj.getTotalCents() + "c");
        assertEquals(InvoiceKind.ADJUSTMENT, adj.getKind());
        assertEquals(inv.getId(), adj.getLinkedInvoiceId(), "调整单必须明确关联原账单");
        assertEquals(50L, adj.getDeltaUnits(), "本次新增用量 50 单位");
        assertEquals(List.of("EVT-LATE-1"), adj.getBasisEventIds(), "计价依据为本次补报事件");
        // 重算口径：用量300 超200 -> 100*5+100*3=800 -> total 1800；补差 = 1800-1650 = 150
        assertEquals(150L, adj.getTotalCents());
        assertEquals(adj.getTotalCents(),
                adj.getPlanFeeCents() + adj.getUsageFeeCents(), "调整单总额守恒");

        // 调整单像普通账单一样收款；重复确认零副作用
        fx.billingService.confirmPayment(adj.getId());
        fx.billingService.confirmPayment(adj.getId());
        assertEquals(InvoiceStatus.PAID, adj.getStatus());
        assertEquals(1, fx.gateway.recordsOf(adj.getId()).size(), "调整单只扣款一次");

        // 再次补报 +100 -> 第二张补差：重算 2100 - (1650+150) = 300
        fx.usageService.report("EVT-LATE-2", sub.getId(), 100L, p0 + fx.days(20));
        List<Invoice> adjs2 = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        assertEquals(2, adjs2.size());
        Invoice adj2 = adjs2.get(1);
        System.out.println("[case] input late +100 state adj2=" + adj2.getId()
                + " deltaUnits=" + adj2.getDeltaUnits() + " total=" + adj2.getTotalCents() + "c");
        assertEquals(100L, adj2.getDeltaUnits());
        assertEquals(300L, adj2.getTotalCents());

        // 对账：原账单 + 补差合计 = 当前重算应收，差额为零且可复现
        ReconciliationStatement stmt = fx.billingService.reconcile(sub.getId(), p0);
        ReconciliationStatement stmt2 = fx.billingService.reconcile(sub.getId(), p0);
        System.out.println("[case] reconcile original=" + stmt.originalTotalCents()
                + "c adjustments=" + stmt.adjustmentInvoiceIds() + " sum="
                + stmt.adjustmentTotalCents() + "c recomputed=" + stmt.recomputedTotalCents()
                + "c discrepancy=" + stmt.discrepancyCents() + "c");
        assertEquals(1650L, stmt.originalTotalCents());
        assertEquals(450L, stmt.adjustmentTotalCents());
        assertEquals(2100L, stmt.recomputedTotalCents());
        assertEquals(0L, stmt.discrepancyCents(), "对账差额必须为零");
        assertTrue(stmt.balanced());
        assertEquals(stmt, stmt2, "对账结果可复现");
    }

    /**
     * 幂等与并发：同一批补报重复触发、8 线程并发触发只生成一张补差；
     * 8 线程并发确认收款只扣款一次；重复事件上报被 DUPLICATE_EVENT 拒绝且不产生新补差。
     */
    @Test
    @DisplayName("幂等: 重复/并发触发不重复生成补差，并发确认不重复扣款")
    void settledLateReport_duplicateAndConcurrentTriggers_noDoubleBilling() throws Exception {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 250L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), p0);
        fx.billingService.confirmPayment(inv.getId());
        fx.usageService.report("EVT-LATE-1", sub.getId(), 50L, p0 + fx.days(10));
        assertEquals(1, fx.invoiceRepository.findAdjustments(sub.getId(), p0).size());

        // 重复触发（显式调用，模拟重试/重投）
        for (int i = 0; i < 3; i++) {
            assertNull(fx.billingService.processLateUsage(sub.getId(), p0 + fx.days(10)),
                    "无新增迟到事件时重复触发必须零副作用");
        }
        // 并发触发：8 线程同时处理同一账期的迟到补报
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (fx.billingService.processLateUsage(sub.getId(), p0 + fx.days(10)) != null) {
                        created.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<Invoice> adjs = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        System.out.println("[case] input duplicate x3 + concurrent x8 state adjustments="
                + adjs.size() + " createdInRace=" + created.get());
        assertEquals(1, adjs.size(), "重复/并发触发不得重复生成补差调整单");
        assertEquals(0, created.get(), "补差已在首次触发时生成，并发触发不得再出单");

        // 重复事件上报：拒绝且不产生新补差
        BillingException dup = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-LATE-1", sub.getId(), 50L, p0 + fx.days(10)));
        System.out.println("[case] input duplicate event decision=" + dup.getCode());
        assertEquals(ErrorCode.DUPLICATE_EVENT, dup.getCode());
        assertEquals(1, fx.invoiceRepository.findAdjustments(sub.getId(), p0).size());

        // 并发确认收款：8 线程同时 confirm，只能有一次真实扣款
        Invoice adj = adjs.get(0);
        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        CountDownLatch ready2 = new CountDownLatch(threads);
        CountDownLatch go2 = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool2.submit(() -> {
                ready2.countDown();
                try {
                    go2.await();
                    fx.billingService.confirmPayment(adj.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready2.await();
        go2.countDown();
        pool2.shutdown();
        assertTrue(pool2.awaitTermination(30, TimeUnit.SECONDS));
        int charges = fx.gateway.recordsOf(adj.getId()).size();
        System.out.println("[case] input concurrent confirm x8 state status=" + adj.getStatus()
                + " charges=" + charges + " records=" + fx.gateway.recordsOf(adj.getId()));
        assertEquals(InvoiceStatus.PAID, adj.getStatus());
        assertEquals(1, charges, "并发确认只能产生一次真实扣款");
        assertEquals(1, adj.getAttempt(), "尝试序号不重复累加");

        ReconciliationStatement stmt = fx.billingService.reconcile(sub.getId(), p0);
        assertEquals(0L, stmt.discrepancyCents(), "并发交错后对账差额仍为零");
    }

    /**
     * 未结清补报：账单 PENDING 时迟到 +150 -> 账单金额重算为 1590，
     * 与按事件重新汇总的结果完全一致，重算审计可说明差异来源；之后收款按新金额扣一次。
     */
    @Test
    @DisplayName("未结清补报: 账单金额重算与事件汇总一致，差异来源可核对")
    void unsettledLateReport_recalculatesAndMatchesAggregation() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 80L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), p0);
        System.out.println("[case] input BASIC used=80 state invoice=" + inv.getId()
                + " total=" + inv.getTotalCents() + "c status=" + inv.getStatus());
        assertEquals(1000L, inv.getTotalCents());
        assertEquals(InvoiceStatus.PENDING, inv.getStatus());

        // 未结清时迟到补报 +150：账单侧必须反映到最终应收金额
        fx.usageService.report("EVT-LATE-1", sub.getId(), 150L, p0 + fx.days(10));
        long aggregated = fx.usageService.aggregate(sub.getId(), p0, sub.getPeriodEnd());
        System.out.println("[case] input late +150 state total=" + inv.getTotalCents()
                + "c recalculations=" + inv.getRecalculations()
                + " decision aggregated=" + aggregated);
        // 重算口径：用量230 超130 -> 100*5+30*3=590 -> total 1590
        assertEquals(1590L, inv.getTotalCents(), "账单金额必须反映补报后的重算结果");
        assertEquals(230L, aggregated);
        assertEquals(1, inv.getRecalculations().size());
        var rec = inv.getRecalculations().get(0);
        assertEquals(1000L, rec.oldTotalCents());
        assertEquals(1590L, rec.newTotalCents());
        assertEquals(150L, rec.deltaUnits());
        assertEquals(List.of("EVT-LATE-1"), rec.causeEventIds(), "差异来源事件可核对");

        // 重复触发零副作用：金额不再变化、审计不追加
        assertEquals(inv, fx.billingService.processLateUsage(sub.getId(), p0 + fx.days(10)));
        assertEquals(1590L, inv.getTotalCents());
        assertEquals(1, inv.getRecalculations().size());
        assertTrue(fx.invoiceRepository.findAdjustments(sub.getId(), p0).isEmpty(),
                "未结清账期不得生成补差调整单");

        // 收款按重算后金额扣款一次；对账差额为零
        fx.billingService.confirmPayment(inv.getId());
        var records = fx.gateway.recordsOf(inv.getId());
        System.out.println("[case] confirm state status=" + inv.getStatus()
                + " charges=" + records.size() + " amount=" + records.get(0).amountCents + "c");
        assertEquals(InvoiceStatus.PAID, inv.getStatus());
        assertEquals(1, records.size());
        assertEquals(1590L, records.get(0).amountCents, "扣款金额必须等于重算后的应收");

        ReconciliationStatement stmt = fx.billingService.reconcile(sub.getId(), p0);
        System.out.println("[case] reconcile original=" + stmt.originalTotalCents()
                + "c adjustments=" + stmt.adjustmentInvoiceIds()
                + " recomputed=" + stmt.recomputedTotalCents()
                + "c discrepancy=" + stmt.discrepancyCents() + "c");
        assertEquals(1590L, stmt.originalTotalCents());
        assertEquals(0L, stmt.adjustmentTotalCents());
        assertEquals(0L, stmt.discrepancyCents());
    }

    /**
     * 不凭空出单：没有新增迟到用量时显式触发不生成补差；
     * 补报用量仍在已计费口径内（差额为零）时也不生成补差，但对账仍平衡。
     */
    @Test
    @DisplayName("边界: 无新增迟到用量或差额为零时不生成补差调整单")
    void noNewLateUsageOrZeroDelta_noAdjustmentCreated() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.usageService.report("EVT-1", sub.getId(), 80L, p0 + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), p0);
        fx.billingService.confirmPayment(inv.getId());

        // 无新增事件，显式触发 -> 不生成补差
        assertNull(fx.billingService.processLateUsage(sub.getId(), p0 + fx.days(5)));
        assertTrue(fx.invoiceRepository.findAdjustments(sub.getId(), p0).isEmpty());

        // 补报 +10：总量90仍在包含额度100内 -> 差额为零 -> 不生成补差
        fx.usageService.report("EVT-LATE-1", sub.getId(), 10L, p0 + fx.days(10));
        List<Invoice> adjs = fx.invoiceRepository.findAdjustments(sub.getId(), p0);
        System.out.println("[case] input late +10 (within quota) state adjustments="
                + adjs.size() + " aggregated="
                + fx.usageService.aggregate(sub.getId(), p0, sub.getPeriodEnd()));
        assertTrue(adjs.isEmpty(), "差额为零不得凭空生成补差调整单");

        ReconciliationStatement stmt = fx.billingService.reconcile(sub.getId(), p0);
        System.out.println("[case] reconcile original=" + stmt.originalTotalCents()
                + "c recomputed=" + stmt.recomputedTotalCents()
                + "c discrepancy=" + stmt.discrepancyCents() + "c");
        assertEquals(0L, stmt.discrepancyCents(), "额度内补报后原账单口径仍与重算一致");
    }

    /**
     * 可受理边界：早于订阅创建、超出未来容忍窗口的补报仍被可区分原因拒绝，
     * 且不会在账单侧留下任何补差痕迹。
     */
    @Test
    @DisplayName("边界: 超出可受理时间范围的补报被明确拒绝且不产生补差")
    void outOfWindowLateReport_rejectedWithDistinctReasons() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long p0 = sub.getPeriodStart();
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), p0);
        fx.billingService.confirmPayment(inv.getId());

        BillingException tooOld = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-OLD", sub.getId(), 10L, p0 - 1L));
        System.out.println("[case] input occurredAt<createdAt decision=" + tooOld.getCode());
        assertEquals(ErrorCode.EVENT_TOO_OLD, tooOld.getCode());

        long beyondWindow = fx.clock.nowMillis()
                + com.github.highcumontoa.subscriptionbillingjava.service.UsageService
                        .OUT_OF_ORDER_TOLERANCE_MILLIS + 1L;
        BillingException outOfWindow = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-FUTURE", sub.getId(), 10L, beyondWindow));
        System.out.println("[case] input occurredAt>now+60d decision=" + outOfWindow.getCode());
        assertEquals(ErrorCode.EVENT_OUT_OF_ORDER_WINDOW, outOfWindow.getCode());

        assertTrue(fx.invoiceRepository.findAdjustments(sub.getId(), p0).isEmpty(),
                "被拒绝的补报不得产生补差调整单");
        ReconciliationStatement stmt = fx.billingService.reconcile(sub.getId(), p0);
        assertEquals(0L, stmt.discrepancyCents());
    }
}
