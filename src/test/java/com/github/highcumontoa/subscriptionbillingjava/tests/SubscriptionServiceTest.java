package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;

import com.github.highcumontoa.subscriptionbillingjava.domain.PlanSegment;
import com.github.highcumontoa.subscriptionbillingjava.domain.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionServiceTest {

    private BillingTestFixtures fx() {
        return BillingTestFixtures.create();
    }

    @Test
    @DisplayName("非法状态流转: 已取消订阅再次取消/变更 -> ILLEGAL_STATE_TRANSITION")
    void cancelledSubscription_rejectsFurtherChanges() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long cancelAt = sub.getPeriodStart() + fx.days(5);
        fx.clock.advance(fx.days(5));
        fx.subscriptionService.cancel(sub.getId(), cancelAt, sub.getVersion());

        BillingException ex1 = assertThrows(BillingException.class,
                () -> fx.subscriptionService.cancel(sub.getId(),
                        cancelAt + fx.days(1), 1L));
        BillingException ex2 = assertThrows(BillingException.class,
                () -> fx.subscriptionService.changePlan(sub.getId(), "PRO",
                        cancelAt + fx.days(1), 1L));
        System.out.println("[case] input cancel-then-cancel/change decision codes="
                + ex1.getCode() + "," + ex2.getCode());
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, ex1.getCode());
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, ex2.getCode());
    }

    @Test
    @DisplayName("约束拒绝原因可区分: 生效时间越界/套餐未变/套餐不存在 各自独立错误码")
    void rejectionCodes_areDistinguishable() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());

        BillingException badTime = assertThrows(BillingException.class,
                () -> fx.subscriptionService.changePlan(sub.getId(), "PRO",
                        sub.getPeriodEnd() + 1, sub.getVersion()));
        BillingException unchanged = assertThrows(BillingException.class,
                () -> fx.subscriptionService.changePlan(sub.getId(), "BASIC",
                        sub.getPeriodStart() + fx.days(1), sub.getVersion()));
        BillingException noPlan = assertThrows(BillingException.class,
                () -> fx.subscriptionService.changePlan(sub.getId(), "GHOST",
                        sub.getPeriodStart() + fx.days(1), sub.getVersion()));

        System.out.println("[case] decision badTime=" + badTime.getCode()
                + " unchanged=" + unchanged.getCode() + " noPlan=" + noPlan.getCode());
        assertEquals(ErrorCode.EFFECTIVE_TIME_INVALID, badTime.getCode());
        assertEquals(ErrorCode.PLAN_UNCHANGED, unchanged.getCode());
        assertEquals(ErrorCode.PLAN_NOT_FOUND, noPlan.getCode());
    }

    @Test
    @DisplayName("降级配额: 生效点前用量超过新套餐比例包含额度 -> DOWNGRADE_QUOTA_EXCEEDED")
    void downgradeOverQuota_rejected() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        // 第10天升级 PRO，第12天产生 200 单位用量，第15天想降回 BASIC
        long t10 = sub.getPeriodStart() + fx.days(10);
        fx.clock.set(t10);
        fx.subscriptionService.changePlan(sub.getId(), "PRO", t10, sub.getVersion());
        long t12 = sub.getPeriodStart() + fx.days(12);
        fx.clock.set(t12);
        fx.usageService.report("EVT-1", sub.getId(), 200L, t12);
        long t15 = sub.getPeriodStart() + fx.days(15);
        fx.clock.set(t15);

        BillingException ex = assertThrows(BillingException.class,
                () -> fx.subscriptionService.changePlan(sub.getId(), "BASIC", t15, 1L));
        System.out.println("[case] input used=200 before downgrade decision=" + ex.getCode()
                + " detail=" + ex.getMessage());
        assertEquals(ErrorCode.DOWNGRADE_QUOTA_EXCEEDED, ex.getCode());
        // 状态与版本未被失败操作污染
        var after = fx.subscriptionRepository.findById(sub.getId());
        assertEquals(1L, after.getVersion(), "被拒绝的降级不得推进版本号");
        assertEquals("PRO", after.getSegments().get(after.getSegments().size() - 1).planCode());
    }

    @Test
    @DisplayName("并发套餐变更: 同一订阅两个并发变更只有一个成功，另一个 CONCURRENT_MODIFICATION；不重复计费")
    void concurrentChange_onlyOneSucceeds() throws Exception {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long baseVersion = sub.getVersion();
        long effectiveAt = sub.getPeriodStart() + fx.days(10);
        fx.clock.advance(fx.days(10));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger concurrentReject = new AtomicInteger();
        AtomicInteger otherReject = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 所有线程都拿着同一个 baseVersion 做 CAS
                    fx.subscriptionService.changePlan(sub.getId(), "PRO", effectiveAt, baseVersion);
                    success.incrementAndGet();
                } catch (BillingException e) {
                    if (e.getCode() == ErrorCode.CONCURRENT_MODIFICATION) {
                        concurrentReject.incrementAndGet();
                    } else {
                        otherReject.incrementAndGet();
                        System.out.println("[case] unexpected reject thread=" + idx
                                + " code=" + e.getCode());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        var finalSub = fx.subscriptionRepository.findById(sub.getId());
        System.out.println("[case] input 8 concurrent changePlan(v=" + baseVersion
                + ") state={finalVersion=" + finalSub.getVersion()
                + ", segmentCount=" + finalSub.getSegments().size()
                + ", changes=" + fx.subscriptionRepository.changesOf(sub.getId()).size()
                + "} decision success=" + success.get()
                + " concurrentReject=" + concurrentReject.get()
                + " otherReject=" + otherReject.get());
        assertEquals(1, success.get(), "恰好一个变更成功");
        assertEquals(threads - 1, concurrentReject.get(), "其余全部以并发冲突被拒绝");
        assertEquals(0, otherReject.get());
        assertEquals(baseVersion + 1, finalSub.getVersion(), "版本只推进一次(无丢失更新)");
        // 分段恰好两段（不存在重复截断/重复开口段，杜绝重复计费）
        assertEquals(2, finalSub.getSegments().size());
        PlanSegment first = finalSub.getSegments().get(0);
        PlanSegment second = finalSub.getSegments().get(1);
        assertEquals("BASIC", first.planCode());
        assertEquals("PRO", second.planCode());
        assertEquals(effectiveAt, first.end());
        assertEquals(effectiveAt, second.start());
    }

    @Test
    @DisplayName("周期内补报式变更: 第二次变更生效时间更早，按生效点重切分段且结果确定")
    void retroactiveChange_resegmentsDeterministically() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long t15 = sub.getPeriodStart() + fx.days(15);
        long t10 = sub.getPeriodStart() + fx.days(10);
        fx.clock.advance(fx.days(16));
        fx.subscriptionService.changePlan(sub.getId(), "PRO", t15, sub.getVersion());
        // 补报第10天起改 ENTERPRISE（晚于上一变更提交，但生效时间更早）
        fx.subscriptionService.changePlan(sub.getId(), "ENTERPRISE", t10, 1L);

        var s2 = fx.subscriptionRepository.findById(sub.getId());
        System.out.println("[case] input change@15d=PRO then retroactive@10d=ENTERPRISE state="
                + s2.getSegments() + " version=" + s2.getVersion());
        assertEquals(2, s2.getSegments().size());
        assertEquals("BASIC", s2.getSegments().get(0).planCode());
        assertEquals("ENTERPRISE", s2.getSegments().get(1).planCode());
        assertEquals(t10, s2.getSegments().get(0).end());
        assertEquals(t10, s2.getSegments().get(1).start());
        assertEquals(2L, s2.getVersion(), "两次成功变更版本推进到2");
    }

    @Test
    @DisplayName("续订: 账期结束后滚动，开口段在边界切分并延续，比例可按账期独立核对")
    void renew_rollsPeriodAndSplitsSegment() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long firstStart = sub.getPeriodStart();
        long firstEnd = sub.getPeriodEnd();
        fx.clock.set(firstEnd + fx.days(1));
        Subscription renewed = fx.subscriptionService.renew(sub.getId(), fx.clock.nowMillis());

        System.out.println("[case] input renew at " + fx.clock.nowMillis()
                + " state period=[" + renewed.getPeriodStart() + "," + renewed.getPeriodEnd()
                + ") segments=" + renewed.getSegments());
        assertEquals(firstEnd, renewed.getPeriodStart());
        assertEquals(2, renewed.getSegments().size());
        assertEquals(firstStart, renewed.getSegments().get(0).start());
        assertEquals(firstEnd, renewed.getSegments().get(0).end());
        assertEquals(firstEnd, renewed.getSegments().get(1).start());
    }
}
