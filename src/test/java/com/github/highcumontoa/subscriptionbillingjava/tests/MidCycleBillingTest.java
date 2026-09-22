package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.Money;
import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.BillLineItem;
import com.github.highcumontoa.subscriptionbillingjava.model.PlanChange;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账期内变更按生效时间比例结算：
 * - 基础费 = 各片段月费 × 片段秒数/账期秒数，HALF_UP；
 * - 变更流水上的比例差额 = 新旧套餐对剩余时段的基础费差额；
 * - 取消后最后片段按取消时刻截断；续订开启新账期。
 */
@SpringBootTest
class MidCycleBillingTest {

    private static final Logger log = LoggerFactory.getLogger(MidCycleBillingTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private BillingService billingService;
    @Autowired private TimeService timeService;

    @Test
    void midCycleUpgrade_proportionalBaseFee_andDeltaMatches() {
        Instant t0 = Instant.parse("2026-04-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("BASIC"); // 1000 分/月

        Instant eff = Instant.parse("2026-04-16T00:00:00Z");
        timeService.setClock(Clock.fixed(eff, ZoneOffset.UTC));
        PlanChange change = subscriptionService.changePlan(sub.getId(), "PRO", eff, 0L); // 5000 分/月

        long total = Duration.between(t0, t0.plusSeconds(0)).getSeconds();
        long periodSeconds = Duration.between(t0, Instant.parse("2026-05-01T00:00:00Z")).getSeconds();
        long firstSeconds = Duration.between(t0, eff).getSeconds();
        long remainingSeconds = periodSeconds - firstSeconds;
        long expectFirst = Money.prorate(1000, firstSeconds, periodSeconds);
        long expectRestOld = Money.prorate(1000, remainingSeconds, periodSeconds);
        long expectRestNew = Money.prorate(5000, remainingSeconds, periodSeconds);
        long expectDelta = expectRestNew - expectRestOld;

        log.info("[输入] 账期={}s 前段={}s 剩余={}s；变更流水差额={}分，理论差额={}分",
                periodSeconds, firstSeconds, remainingSeconds,
                change.proratedDeltaCents(), expectDelta);
        assertEquals(expectDelta, change.proratedDeltaCents());

        Bill bill = billingService.generateBill(sub.getId(), 0);
        long itemSum = bill.getLineItems().stream().mapToLong(BillLineItem::amountCents).sum();
        log.info("[判定] 账单明细={} 总额={}分 明细和={}分",
                bill.getLineItems(), bill.getTotalCents(), itemSum);
        assertEquals(itemSum, bill.getTotalCents(), "总额守恒");
        assertEquals(expectFirst + expectRestNew, bill.getTotalCents(),
                "基础费按生效时间比例分摊");
        assertEquals(2, bill.getLineItems().size());
    }

    @Test
    void cancelSettlesProportionally_andRenewOpensNewPeriod() {
        Instant t0 = Instant.parse("2026-04-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("BASIC");

        Instant cancelAt = Instant.parse("2026-04-11T00:00:00Z");
        timeService.setClock(Clock.fixed(cancelAt, ZoneOffset.UTC));
        Subscription cancelled = subscriptionService.cancel(sub.getId(), cancelAt);

        Bill bill0 = billingService.generateBill(sub.getId(), 0);
        long periodSeconds = Duration.between(t0, Instant.parse("2026-05-01T00:00:00Z")).getSeconds();
        long activeSeconds = Duration.between(t0, cancelAt).getSeconds();
        long expect = Money.prorate(1000, activeSeconds, periodSeconds);
        log.info("[取消结算] 生效={}s/账期={}s -> 基础费期望≈{}分，账单总额={}分 明细={}",
                activeSeconds, periodSeconds, expect, bill0.getTotalCents(), bill0.getLineItems());
        assertEquals(expect, bill0.getTotalCents());
        assertTrue(bill0.getTotalCents() < 1000, "提前取消费用必须小于整月");

        // 账期到期后续订：订阅已取消应被拒绝；另建一个活跃订阅验证续订账期
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription active = subscriptionService.create("PRO");
        timeService.setClock(Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC));
        PlanChange renew = subscriptionService.renew(active.getId());
        log.info("[续订] 新账期起={} 止={} segments={}",
                active.getCurrentPeriodStart(), active.getCurrentPeriodEnd(),
                active.getSegments().size());
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), active.getCurrentPeriodStart());
        Bill bill1 = billingService.generateBill(active.getId(), 1);
        assertEquals(5000, bill1.getTotalCents(), "续订账期全额 PRO 基础费");
    }
}
