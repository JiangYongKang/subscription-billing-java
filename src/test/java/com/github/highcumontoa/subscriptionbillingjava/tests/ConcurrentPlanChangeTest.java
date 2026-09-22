package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.error.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.BillStatus;
import com.github.highcumontoa.subscriptionbillingjava.model.PlanChange;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.service.SubscriptionService;
import com.github.highcumontoa.subscriptionbillingjava.store.PlanChangeRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.SubscriptionRepository;
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
 * 同一订阅并发套餐变更：只有一个成功，另一个得到可区分的 CONCURRENT_CHANGE_CONFLICT；
 * 账期账单重算结果与“串行执行”完全一致（不重复计费、不丢更新）。
 */
@SpringBootTest
class ConcurrentPlanChangeTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentPlanChangeTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private BillingService billingService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PlanChangeRepository planChangeRepository;
    @Autowired private TimeService timeService;

    @Test
    void concurrentChanges_onlyOneCommits_andBillMatchesSerial() throws Exception {
        Instant t0 = Instant.parse("2026-03-10T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        Subscription sub = subscriptionService.create("BASIC");
        long version = sub.getVersion();
        log.info("[输入] 订阅={} 初始套餐=BASIC version={}，两个线程同时升级 PRO，期望一个成功一个冲突",
                sub.getId(), version);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        Runnable task = () -> {
            try {
                start.await();
                subscriptionService.changePlan(sub.getId(), "PRO", null, version);
                success.incrementAndGet();
            } catch (BillingException e) {
                log.warn("[并发结果] 线程={} 被拒绝 code={} msg={}",
                        Thread.currentThread().getName(), e.getErrorCode(), e.getMessage());
                if (e.getErrorCode() == ErrorCode.CONCURRENT_CHANGE_CONFLICT) {
                    conflict.incrementAndGet();
                } else {
                    other.incrementAndGet();
                }
            } catch (Exception e) {
                other.incrementAndGet();
            }
        };
        pool.submit(task);
        pool.submit(task);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[判定] success={} conflict={} other={}", success.get(), conflict.get(), other.get());
        assertEquals(1, success.get(), "恰好一次变更成功");
        assertEquals(1, conflict.get(), "另一次必须是版本冲突");
        assertEquals(0, other.get());

        Subscription after = subscriptionRepository.find(sub.getId()).orElseThrow();
        assertEquals("PRO", after.getPlanCode());
        assertEquals(version + 1, after.getVersion(), "version 只前进一次，无丢失更新");
        assertEquals(1, planChangeRepository.countBySubscription(sub.getId()),
                "只有一条变更流水，不会重复计费");

        Bill bill = billingService.generateBill(sub.getId(), 0);
        long itemSum = bill.getLineItems().stream().mapToLong(i -> i.amountCents()).sum();
        log.info("[账单核对] 明细={} 总额={}分 明细之和={}分（守恒）",
                bill.getLineItems(), bill.getTotalCents(), itemSum);
        assertEquals(itemSum, bill.getTotalCents());
        // BASIC 前 0 秒 + PRO 整账期：升级即时生效（eff=t0 即账期锚点），基础费≈5000
        assertEquals(5000, bill.getTotalCents());
        assertEquals(BillStatus.DRAFT, bill.getStatus());
    }

    @Test
    void invalidTransitions_haveDistinctReasons() {
        Instant t0 = Instant.parse("2026-03-10T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription s = subscriptionService.create("PRO");

        // 相同套餐
        expect(ErrorCode.SAME_PLAN_CHANGE,
                () -> subscriptionService.changePlan(s.getId(), "PRO", null, s.getVersion()));
        // 不存在的套餐
        expect(ErrorCode.PLAN_NOT_FOUND,
                () -> subscriptionService.changePlan(s.getId(), "NOPE", null, s.getVersion()));
        // 配额约束降级（PRO 5000 -> BASIC 1000）
        expect(ErrorCode.DOWNGRADE_QUOTA_LIMIT,
                () -> subscriptionService.changePlan(s.getId(), "BASIC", null, s.getVersion()));

        Subscription ent = subscriptionService.create("ENTERPRISE");
        // 档位约束降级（配额同为 20000 但禁止跨档直降）
        expect(ErrorCode.DOWNGRADE_NOT_ALLOWED_TIER,
                () -> subscriptionService.changePlan(ent.getId(), "SMART", null, ent.getVersion()));

        // 取消后禁止变更
        subscriptionService.cancel(ent.getId(), null);
        expect(ErrorCode.SUBSCRIPTION_CANCELLED,
                () -> subscriptionService.changePlan(ent.getId(), "PRO", null, ent.getVersion()));
        log.info("[判定] SAME_PLAN/PLAN_NOT_FOUND/QUOTA/TIER/CANCELLED 五类拒绝原因均可区分");
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
