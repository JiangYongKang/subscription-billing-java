package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.billing.RetryPolicy;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DunningServiceTest {

    private BillingTestFixtures fx() {
        return BillingTestFixtures.create();
    }

    private Invoice pendingInvoice(BillingTestFixtures fx) {
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        fx.clock.set(sub.getPeriodEnd() + 1L);
        return fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());
    }

    @Test
    @DisplayName("首跳失败: confirm 扣款被拒 -> DUNNING，attempt=1，nextRetryAt=now+1d")
    void firstFailure_entersDunning() {
        var fx = fx();
        Invoice inv = pendingInvoice(fx);
        long failedAt = fx.clock.nowMillis();
        fx.gateway.prime(inv.getId(), false, "INSUFFICIENT_FUNDS");

        Invoice after = fx.billingService.confirmPayment(inv.getId());
        System.out.println("[case] input decline first charge state status=" + after.getStatus()
                + " attempt=" + after.getAttempt() + " nextRetryAt=" + after.getNextRetryAt()
                + " expected=" + (failedAt + fx.days(1))
                + " charges=" + fx.gateway.recordsOf(inv.getId()).size());
        assertEquals(InvoiceStatus.DUNNING, after.getStatus());
        assertEquals(1, after.getAttempt());
        assertEquals(failedAt + fx.days(1), after.getNextRetryAt());
        assertEquals(1, fx.gateway.recordsOf(inv.getId()).size());
    }

    @Test
    @DisplayName("未到期重试: now < nextRetryAt -> RETRY_NOT_DUE，不产生扣款")
    void retryBeforeDue_rejected() {
        var fx = fx();
        Invoice inv = pendingInvoice(fx);
        fx.gateway.prime(inv.getId(), false, "INSUFFICIENT_FUNDS");
        fx.billingService.confirmPayment(inv.getId());

        BillingException ex = assertThrows(BillingException.class,
                () -> fx.dunningService.processRetry(inv.getId()));
        System.out.println("[case] input immediate retry state nextRetryAt="
                + inv.getNextRetryAt() + " now=" + fx.clock.nowMillis()
                + " decision=" + ex.getCode());
        assertEquals(ErrorCode.RETRY_NOT_DUE, ex.getCode());
        assertEquals(1, fx.gateway.recordsOf(inv.getId()).size(), "未到期不得扣款");
    }

    @Test
    @DisplayName("重试成功: 推进到 nextRetryAt 后重试扣款成功 -> PAID；再重复确认零扣款")
    void retryDue_successThenIdempotent() {
        var fx = fx();
        Invoice inv = pendingInvoice(fx);
        fx.gateway.prime(inv.getId(), false, "INSUFFICIENT_FUNDS"); // 首次失败
        fx.gateway.prime(inv.getId(), true, "OK_RETRY");            // 重试成功
        fx.billingService.confirmPayment(inv.getId());
        assertEquals(InvoiceStatus.DUNNING, inv.getStatus());

        fx.clock.advance(fx.days(1));
        Invoice retried = fx.dunningService.processRetry(inv.getId());
        System.out.println("[case] input retry at +1d state status=" + retried.getStatus()
                + " attempt=" + retried.getAttempt()
                + " charges=" + fx.gateway.recordsOf(inv.getId()).size());
        assertEquals(InvoiceStatus.PAID, retried.getStatus());
        assertEquals(2, retried.getAttempt());

        // 到期后再次重试/确认：幂等，不再扣款
        Invoice again = fx.dunningService.processRetry(inv.getId());
        Invoice again2 = fx.billingService.confirmPayment(inv.getId());
        System.out.println("[case] input retry/confirm after PAID state status="
                + again.getStatus() + " charges=" + fx.gateway.recordsOf(inv.getId()).size()
                + " records=" + fx.gateway.recordsOf(inv.getId()));
        assertEquals(InvoiceStatus.PAID, again.getStatus());
        assertEquals(InvoiceStatus.PAID, again2.getStatus());
        assertEquals(2, fx.gateway.recordsOf(inv.getId()).size(), "PAID 后任何入口都不再扣款");
    }

    @Test
    @DisplayName("重试耗尽: 自定义策略1ms单次间隔，连续失败后 FAILED；再重试 RETRY_EXHAUSTED")
    void retriesExhausted_becomesFailed() {
        var fx = fx();
        // 共 2 次尝试（首扣 + 1 次重试），间隔 1ms
        fx.dunningService.configure(new RetryPolicy(java.util.List.of(1L)));
        Invoice inv = pendingInvoice(fx);
        fx.gateway.prime(inv.getId(), false, "CARD_EXPIRED");
        fx.billingService.confirmPayment(inv.getId());
        assertEquals(InvoiceStatus.DUNNING, inv.getStatus());

        fx.clock.advance(2L);
        Invoice finalState = fx.dunningService.processRetry(inv.getId());
        System.out.println("[case] input second decline state status=" + finalState.getStatus()
                + " attempt=" + finalState.getAttempt()
                + " charges=" + fx.gateway.recordsOf(inv.getId()).size());
        assertEquals(InvoiceStatus.FAILED, finalState.getStatus());
        assertEquals(2, finalState.getAttempt());

        BillingException ex = assertThrows(BillingException.class,
                () -> fx.dunningService.processRetry(inv.getId()));
        System.out.println("[case] input retry after FAILED decision=" + ex.getCode());
        assertEquals(ErrorCode.RETRY_EXHAUSTED, ex.getCode());
        // FAILED 账单走 confirm 入口也被拒绝，且无新增扣款
        BillingException ex2 = assertThrows(BillingException.class,
                () -> fx.billingService.confirmPayment(inv.getId()));
        assertEquals(ErrorCode.INVOICE_NOT_CONFIRMABLE, ex2.getCode());
        assertEquals(2, fx.gateway.recordsOf(inv.getId()).size(), "终态不得再扣款");
    }
}
