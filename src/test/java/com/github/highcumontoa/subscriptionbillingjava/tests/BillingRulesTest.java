package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.Money;
import com.github.highcumontoa.subscriptionbillingjava.billing.UsageAggregator;
import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import com.github.highcumontoa.subscriptionbillingjava.model.PricingTier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯规则单测：比例分摊四舍五入、阶梯边际计价的确定性。
 */
class BillingRulesTest {

    private static final Logger log = LoggerFactory.getLogger(BillingRulesTest.class);

    private Plan basic() {
        return new Plan("BASIC", "基础版", 1000, 1000, List.of(
                new PricingTier(1000, 3),
                new PricingTier(5000, 2),
                new PricingTier(Long.MAX_VALUE, 1)));
    }

    @Test
    void prorate_halfMonth_isHalfUpToCent() {
        long p = Money.prorate(1000, 15, 31);
        log.info("[输入] amount=1000分 part=15 total=31 -> [判定] 1000*15/31 HALF_UP = 484分, 实际={}", p);
        assertEquals(484, p);
    }

    @Test
    void prorate_zeroPart_isZero() {
        assertEquals(0, Money.prorate(1000, 0, 31));
    }

    @Test
    void tieredFee_marginalTiers() {
        Plan plan = basic();
        // 超额 3000：0~1000 档 3分，1000~3000 档 2分 = 3000+4000 = 7000
        long fee3000 = UsageAggregator.tieredFee(plan, 3000);
        log.info("[输入] 超额=3000 阶梯=3分/2分/1分 -> [判定] 1000*3+2000*2=7000分, 实际={}", fee3000);
        assertEquals(7000, fee3000);

        long fee7000 = UsageAggregator.tieredFee(plan, 7000);
        // 1000*3 + 4000*2 + 2000*1 = 13000
        log.info("[输入] 超额=7000 -> [判定] 1000*3+4000*2+2000*1=13000分, 实际={}", fee7000);
        assertEquals(13000, fee7000);

        long zero = UsageAggregator.tieredFee(plan, 0);
        assertEquals(0, zero);
    }

    @Test
    void ratio_isWithinZeroAndOne() {
        assertTrue(Money.ratio(15, 31).doubleValue() > 0.48);
        assertTrue(Money.ratio(15, 31).doubleValue() < 0.49);
    }
}
