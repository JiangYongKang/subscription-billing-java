package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.error.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.BillStatus;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.service.DunningService;
import com.github.highcumontoa.subscriptionbillingjava.service.SubscriptionService;
import com.github.highcumontoa.subscriptionbillingjava.service.UsageService;
import com.github.highcumontoa.subscriptionbillingjava.service.gateway.LocalPaymentGateway;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 收款链路：重复确认幂等不二次扣款；失败按退避重试；未到时间拒绝（RETRY_TOO_EARLY）；
 * 催收扫描自动重试；超过最大次数进入 DUNNING。
 */
@SpringBootTest
class BillPaymentTest {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageService usageService;
    @Autowired private BillingService billingService;
    @Autowired private DunningService dunningService;
    @Autowired private LocalPaymentGateway gateway;
    @Autowired private TimeService timeService;

    @Test
    void duplicateConfirm_isIdempotent_noSecondCharge() {
        Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("BASIC");
        Bill bill = billingService.generateBill(sub.getId(), 0);
        log.info("[输入] 账单={} 总额={}分，同一 idempotencyKey=k-77 确认两次",
                bill.getId(), bill.getTotalCents());

        Bill first = billingService.confirmAndCollect(bill.getId(), "k-77");
        Bill second = billingService.confirmAndCollect(bill.getId(), "k-77");

        log.info("[判定] 第一次 status={} attempts={}；第二次 status={} attempts={}（必须相同）",
                first.getStatus(), first.getAttempts().size(),
                second.getStatus(), second.getAttempts().size());
        assertEquals(BillStatus.PAID, first.getStatus());
        assertEquals(1, first.getAttempts().size(), "只有一次真实扣款尝试");
        assertEquals(1, second.getAttempts().size(), "重复确认不二次扣款");

        // 不同 key 再确认：可区分拒绝
        expect(ErrorCode.BILL_ALREADY_CONFIRMED,
                () -> billingService.confirmAndCollect(bill.getId(), "k-other"));
    }

    @Test
    void failedPayment_retriesWithBackoff_andDunningExhausts() {
        Instant t0 = Instant.parse("2026-07-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("PRO");
        Bill bill = billingService.generateBill(sub.getId(), 0);
        // 三次尝试全部失败（首次确认 + 两次重试）
        gateway.preset(bill.getId(), false, false, false);
        log.info("[输入] 账单={} 预置三次扣款均失败", bill.getId());

        Bill b1 = billingService.confirmAndCollect(bill.getId(), "k-1");
        log.info("[首次] status={} attempts={} nextRetryAt={}（退避1分钟）",
                b1.getStatus(), b1.getAttempts().size(), b1.getNextRetryAt());
        assertEquals(BillStatus.PAYMENT_FAILED, b1.getStatus());
        assertEquals(1, b1.getAttempts().size());
        assertEquals(t0.plusSeconds(60), b1.getNextRetryAt());

        // 未到重试时间
        expect(ErrorCode.PAYMENT_RETRY_TOO_EARLY, () -> billingService.retryPayment(bill.getId()));

        // 推进 60s：催收扫描触发第二次（仍失败，退避10分钟）
        timeService.setClock(Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        Bill b2 = dunningService.runRetryCycle().get(0);
        log.info("[第二次] status={} attempts={} nextRetryAt={}（退避10分钟）",
                b2.getStatus(), b2.getAttempts().size(), b2.getNextRetryAt());
        assertEquals(BillStatus.PAYMENT_FAILED, b2.getStatus());
        assertEquals(2, b2.getAttempts().size());
        assertEquals(t0.plusSeconds(60 + 600), b2.getNextRetryAt());

        // 催收扫描在未到期时不应捞到该账单
        assertEquals(0, dunningService.runRetryCycle().size());

        // 推进到 10 分钟后：第三次失败 -> DUNNING
        timeService.setClock(Clock.fixed(t0.plusSeconds(660), ZoneOffset.UTC));
        Bill b3 = billingService.retryPayment(bill.getId());
        log.info("[第三次] status={} attempts={} dunningNote={}",
                b3.getStatus(), b3.getAttempts().size(), b3.getDunningNote());
        assertEquals(BillStatus.DUNNING, b3.getStatus());
        assertEquals(3, b3.getAttempts().size());

        // 已催收继续重试：可区分拒绝
        expect(ErrorCode.RETRY_EXHAUSTED, () -> billingService.retryPayment(bill.getId()));
    }

    @Test
    void failedThenSuccessOnRetry_becomesPaid() {
        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("BASIC");
        Bill bill = billingService.generateBill(sub.getId(), 0);
        gateway.preset(bill.getId(), false, true);

        Bill b1 = billingService.confirmAndCollect(bill.getId(), "k-9");
        assertEquals(BillStatus.PAYMENT_FAILED, b1.getStatus());

        timeService.setClock(Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        Bill b2 = billingService.retryPayment(bill.getId());
        log.info("[失败后重试成功] status={} attempts={}", b2.getStatus(), b2.getAttempts().size());
        assertEquals(BillStatus.PAID, b2.getStatus());
        assertEquals(2, b2.getAttempts().size());

        // 已支付再重试：幂等返回，不新增尝试
        Bill b3 = billingService.retryPayment(bill.getId());
        assertEquals(2, b3.getAttempts().size());
    }

    private void expect(ErrorCode code, Runnable r) {
        try {
            r.run();
            throw new AssertionError("期望抛出 " + code);
        } catch (BillingException e) {
            log.info("[拒绝校验] 期望={} 实际={} msg={}", code, e.getErrorCode(), e.getMessage());
            assertEquals(code, e.getErrorCode());
        }
    }
}
