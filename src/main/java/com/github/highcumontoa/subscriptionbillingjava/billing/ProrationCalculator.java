package com.github.highcumontoa.subscriptionbillingjava.billing;

import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 比例分摊：账期内某片段的基础费 = 月费 × 片段秒数 / 账期秒数（HALF_UP 到分）。
 */
@Component
public class ProrationCalculator {

    public long proratedBaseCents(Plan plan, Instant segmentStart, Instant segmentEnd,
                                  Instant periodStart, Instant periodEnd) {
        long total = secondsBetween(periodStart, periodEnd);
        long part = secondsBetween(segmentStart, segmentEnd);
        return Money.prorate(plan.amountCents(), part, total);
    }

    public long secondsBetween(Instant start, Instant end) {
        long seconds = Duration.between(start, end).getSeconds();
        return Math.max(0, seconds);
    }
}
