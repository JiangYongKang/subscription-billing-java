package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;

import com.github.highcumontoa.subscriptionbillingjava.domain.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageServiceTest {

    private BillingTestFixtures fx() {
        return BillingTestFixtures.create();
    }

    @Test
    @DisplayName("重复事件: 相同 eventId 二次上报被 DUPLICATE_EVENT 拒绝，不重复计入")
    void duplicateEvent_rejectedNotDoubleCounted() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long t = sub.getPeriodStart() + fx.days(2);
        fx.usageService.report("EVT-DUP", sub.getId(), 10L, t);

        BillingException ex = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-DUP", sub.getId(), 99L, t));
        System.out.println("[case] input duplicate eventId=EVT-DUP qty2=99 decision="
                + ex.getCode() + " detail=" + ex.getMessage());
        assertEquals(ErrorCode.DUPLICATE_EVENT, ex.getCode());
        assertEquals(10L, fx.usageService.aggregate(sub.getId(),
                sub.getPeriodStart(), sub.getPeriodEnd()),
                "重复事件数量不得计入，总量仍是首次的10");
    }

    @Test
    @DisplayName("乱序上报: 先报晚事件再补报早事件，按 occurredAt 归并，聚合结果稳定可复现")
    void outOfOrder_eventsAggregatedByOccurredTime() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long t2 = sub.getPeriodStart() + fx.days(5);
        long t1 = sub.getPeriodStart() + fx.days(1);
        fx.clock.set(t2);
        fx.usageService.report("LATE-T2", sub.getId(), 30L, t2); // 先报第5天
        fx.clock.set(t2 + fx.days(1));
        fx.usageService.report("EARLY-T1", sub.getId(), 20L, t1); // 再补报第1天

        var events = fx.usageService.aggregatedEvents(sub.getId(),
                sub.getPeriodStart(), sub.getPeriodEnd());
        long total = fx.usageService.aggregate(sub.getId(),
                sub.getPeriodStart(), sub.getPeriodEnd());
        System.out.println("[case] input report order t2(30)->t1(20) state basis="
                + events.stream().map(e -> e.eventId() + "@" + e.occurredAt() + "=" + e.quantity())
                        .toList()
                + " decision total=" + total);
        assertEquals(2, events.size());
        assertEquals("EARLY-T1", events.get(0).eventId(), "排序应按 occurredAt，早事件在前");
        assertEquals(50L, total);

        // 再次聚合口径完全一致（不随调用次数/上报顺序变化）
        assertEquals(50L, fx.usageService.aggregate(sub.getId(),
                sub.getPeriodStart(), sub.getPeriodEnd()));
    }

    @Test
    @DisplayName("迟到补报: 账期事件多天后补报仍计入并进入重新聚合，多次聚合数值稳定")
    void lateArrival_includedAndStable() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long tInPeriod = sub.getPeriodStart() + fx.days(3);
        fx.clock.set(tInPeriod + fx.days(20));
        fx.usageService.report("LATE-1", sub.getId(), 7L, tInPeriod);

        long a1 = fx.usageService.aggregate(sub.getId(), sub.getPeriodStart(), sub.getPeriodEnd());
        long a2 = fx.usageService.aggregate(sub.getId(), sub.getPeriodStart(), sub.getPeriodEnd());
        System.out.println("[case] input late event occurredAt+3d reportedAt+23d "
                + "decision aggregate1=" + a1 + " aggregate2=" + a2);
        assertEquals(7L, a1);
        assertEquals(a1, a2, "补报后重新聚合口径稳定");
    }

    @Test
    @DisplayName("非法事件: 数量非正 QUANTITY_INVALID；早于创建时间 EVENT_TOO_OLD")
    void invalidEvents_distinguishableRejection() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());

        BillingException badQty = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-Q", sub.getId(), 0L, fx.clock.nowMillis()));
        BillingException tooOld = assertThrows(BillingException.class,
                () -> fx.usageService.report("EVT-OLD", sub.getId(), 1L,
                        sub.getCreatedAt() - 1L));
        System.out.println("[case] decision badQty=" + badQty.getCode()
                + " tooOld=" + tooOld.getCode());
        assertEquals(ErrorCode.QUANTITY_INVALID, badQty.getCode());
        assertEquals(ErrorCode.EVENT_TOO_OLD, tooOld.getCode());
        assertEquals(0L, fx.usageService.aggregate(sub.getId(),
                sub.getPeriodStart(), sub.getPeriodEnd()), "非法事件被拒绝不得计入");
    }

    @Test
    @DisplayName("并发重复上报: 同一 eventId 8 线程并发，仅 1 次计入，其余 DUPLICATE_EVENT")
    void concurrentDuplicate_onlyAcceptedOnce() throws Exception {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    fx.usageService.report("EVT-RACE", sub.getId(), 5L,
                            sub.getPeriodStart() + fx.days(1));
                    accepted.incrementAndGet();
                } catch (BillingException e) {
                    if (e.getCode() == ErrorCode.DUPLICATE_EVENT) {
                        duplicate.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long total = fx.usageService.aggregate(sub.getId(),
                sub.getPeriodStart(), sub.getPeriodEnd());
        System.out.println("[case] input 8 concurrent same-eventId state total=" + total
                + " decision accepted=" + accepted.get() + " duplicate=" + duplicate.get()
                + " unexpected=" + unexpected.size());
        assertEquals(1, accepted.get());
        assertEquals(threads - 1, duplicate.get());
        assertEquals(0, unexpected.size());
        assertEquals(5L, total, "同一事件在并发下也只计入一次");
    }
}
