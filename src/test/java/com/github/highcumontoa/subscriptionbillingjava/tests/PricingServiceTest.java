package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;

import com.github.highcumontoa.subscriptionbillingjava.domain.OverageTier;
import com.github.highcumontoa.subscriptionbillingjava.service.PricingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 计价规则单测：打印输入、判定依据，并验证分段比例守恒与阶梯累进正确。 */
class PricingServiceTest {

    private final PricingService pricing = new PricingService();
    private static final long P = BillingTestFixtures.days(30);
    private static final long T0 = 0L;

    @Test
    @DisplayName("比例分摊: 半月分段月费与额度均按 1/2 HALF_UP 分摊，整月两段之和守恒")
    void prorated_halfMonth_sumsToMonthlyTotal() {
        long half = BillingTestFixtures.days(15);
        System.out.println("[case] input monthly=3000c included=500 splitAt=15d(半月)");

        long fee1 = pricing.proratedMonthlyPrice(3000, T0, half, T0, P);
        long fee2 = pricing.proratedMonthlyPrice(3000, half, P, T0, P);
        System.out.println("[case] basis fee1=" + fee1 + "c fee2=" + fee2 + "c sum=" + (fee1 + fee2));
        assertEquals(1500, fee1, "半月段比例月费应为1500分");
        assertEquals(1500, fee2, "半月段比例月费应为1500分");
        assertEquals(3000, fee1 + fee2, "两段之和必须等于整月费用(比例守恒)");

        long u1 = pricing.proratedIncludedUnits(500, T0, half, T0, P);
        long u2 = pricing.proratedIncludedUnits(500, half, P, T0, P);
        System.out.println("[case] decision included1=" + u1 + " included2=" + u2
                + " sum=" + (u1 + u2));
        assertEquals(250, u1);
        assertEquals(250, u2);
    }

    @Test
    @DisplayName("比例分摊: 1/3 与 2/3 分段 HALF_UP 取整后差额不超过1，确定可复现")
    void prorated_thirds_isDeterministic() {
        long t1 = BillingTestFixtures.days(10);
        long t2 = BillingTestFixtures.days(20);
        long fee1 = pricing.proratedMonthlyPrice(3000, T0, t1, T0, P);
        long fee2 = pricing.proratedMonthlyPrice(3000, t1, t2, T0, P);
        long fee3 = pricing.proratedMonthlyPrice(3000, t2, P, T0, P);
        long sum = fee1 + fee2 + fee3;
        System.out.println("[case] input 3000c / 3 segments(10d,10d,10d) decision fees="
                + fee1 + "," + fee2 + "," + fee3 + " sum=" + sum);
        assertEquals(1000, fee1);
        assertEquals(1000, fee2);
        assertEquals(1000, fee3);
        assertEquals(3000, sum);
        // 再算一次必须完全一致（纯函数可复现）
        assertEquals(fee1, pricing.proratedMonthlyPrice(3000, T0, t1, T0, P));
    }

    @Test
    @DisplayName("阶梯计价: 0..100 单价5分、100以上3分，250单位费用=100*5+150*3=950")
    void overageTier_progressive() {
        List<OverageTier> tiers = List.of(
                new OverageTier(0L, 100L, 5L),
                new OverageTier(100L, null, 3L));
        long fee = pricing.overageFee(tiers, 250);
        System.out.println("[case] input overageUnits=250 tiers=[(0,100]@5,(100,∞)@3] "
                + "decision fee=" + fee + "c expected=950c");
        assertEquals(950, fee);
        assertEquals(0, pricing.overageFee(tiers, 0), "零超用费用为0");
        // 重复计算结果稳定
        assertEquals(fee, pricing.overageFee(tiers, 250));
    }
}
