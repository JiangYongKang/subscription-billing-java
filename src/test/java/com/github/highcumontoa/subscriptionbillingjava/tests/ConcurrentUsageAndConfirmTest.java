package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.error.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.BillStatus;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.service.SubscriptionService;
import com.github.highcumontoa.subscriptionbillingjava.service.UsageService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发幂等：同一 eventId 并发上报只入库一次；同一 idempotencyKey 并发确认只扣款一次。
 */
@SpringBootTest
class ConcurrentUsageAndConfirmTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentUsageAndConfirmTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageService usageService;
    @Autowired private BillingService billingService;
    @Autowired private TimeService timeService;

    @Test
    void concurrentDuplicateEvents_onlyOneAccepted() throws Exception {
        Instant t0 = Instant.parse("2026-09-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0.plusSeconds(100), ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("BASIC");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicated = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    usageService.report("same-event", sub.getId(), 100, t0.plusSeconds(50));
                    accepted.incrementAndGet();
                } catch (BillingException e) {
                    if (e.getErrorCode() == ErrorCode.DUPLICATE_USAGE_EVENT) {
                        duplicated.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[输入] {} 个线程并发上报同一 eventId=same-event -> 接受={} 重复拒绝={}",
                threads, accepted.get(), duplicated.get());
        assertEquals(1, accepted.get());
        assertEquals(threads - 1, duplicated.get());

        Bill bill = billingService.generateBill(sub.getId(), 0);
        // 用量 100 <= 1000 配额，仅基础费；证明事件只计入一次
        assertEquals(1000, bill.getTotalCents());
    }

    @Test
    void concurrentDuplicateConfirm_onlyOneCharge() throws Exception {
        Instant t0 = Instant.parse("2026-09-02T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("BASIC");
        Bill bill = billingService.generateBill(sub.getId(), 0);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger paid = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Bill b = billingService.confirmAndCollect(bill.getId(), "same-key");
                    if (b.getStatus() == BillStatus.PAID) {
                        paid.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        Bill after = billingService.getBill(bill.getId());
        log.info("[输入] {} 个线程同 key={} 并发确认 -> PAID 返回次数={} 异常={} 实际扣款尝试={}",
                threads, "same-key", paid.get(), errors.get(), after.getAttempts().size());
        assertEquals(threads, paid.get(), "同 key 全部得到 PAID 结果");
        assertEquals(0, errors.get());
        assertEquals(1, after.getAttempts().size(), "真实扣款恰好一次");
    }
}
