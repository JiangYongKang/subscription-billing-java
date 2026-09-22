package com.github.highcumontoa.subscriptionbillingjava.billing;

import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import com.github.highcumontoa.subscriptionbillingjava.model.PricingTier;
import com.github.highcumontoa.subscriptionbillingjava.model.Segment;
import com.github.highcumontoa.subscriptionbillingjava.model.UsageEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用量聚合：
 * 1. 事件按 occurredAt 落入套餐片段（乱序/迟到事件只要在账期内一律参与，入参已去重）；
 * 2. 片段配额 = 套餐配额 × 片段秒数/账期秒数，向下取整（各片段配额之和不超过全量配额）；
 * 3. 超额量 = max(0, 片段用量 - 片段配额)，按边际阶梯单价逐档计价，结果确定可复现。
 */
@Component
public class UsageAggregator {

    private final ProrationCalculator prorationCalculator;

    public UsageAggregator(ProrationCalculator prorationCalculator) {
        this.prorationCalculator = prorationCalculator;
    }

    public List<SegmentUsage> aggregate(List<UsageEvent> events,
                                        List<Segment> segments,
                                        Map<String, Plan> plans,
                                        Instant periodStart, Instant periodEnd) {
        List<SegmentUsage> result = new ArrayList<>();
        long totalSeconds = prorationCalculator.secondsBetween(periodStart, periodEnd);

        for (Segment raw : segments) {
            Instant segStart = raw.start().isBefore(periodStart) ? periodStart : raw.start();
            Instant segEnd = raw.end().isAfter(periodEnd) ? periodEnd : raw.end();
            if (!segEnd.isAfter(segStart)) {
                continue;
            }
            Plan plan = plans.get(raw.planCode());
            long quantity = events.stream()
                    .filter(e -> !e.occurredAt().isBefore(segStart) && e.occurredAt().isBefore(segEnd))
                    .mapToLong(UsageEvent::quantity)
                    .sum();

            long segSeconds = prorationCalculator.secondsBetween(segStart, segEnd);
            long proratedQuota = BigDecimal.valueOf(plan.quotaIncluded())
                    .multiply(BigDecimal.valueOf(segSeconds))
                    .divide(BigDecimal.valueOf(Math.max(1, totalSeconds)), 0, RoundingMode.DOWN)
                    .longValueExact();
            long overage = Math.max(0, quantity - proratedQuota);
            long overageFee = tieredFee(plan, overage);
            long baseFee = prorationCalculator.proratedBaseCents(plan, segStart, segEnd,
                    periodStart, periodEnd);

            result.add(new SegmentUsage(raw.planCode(), segStart, segEnd,
                    quantity, proratedQuota, overage, baseFee, overageFee));
        }
        return result;
    }

    /**
     * 边际阶梯计价：tier(included, price) 表示超出配额后 (上一档included, 本档included] 区间的
     * 单位单价；included 为 Long.MAX_VALUE 表示末档。
     */
    public static long tieredFee(Plan plan, long overage) {
        long fee = 0L;
        long lower = 0L;
        List<PricingTier> tiers = plan.overageTiers();
        for (int i = 0; i < tiers.size(); i++) {
            PricingTier tier = tiers.get(i);
            if (overage <= lower) {
                break;
            }
            long upper = tier.included();
            long inTier = Math.min(overage, upper) - lower;
            if (inTier > 0) {
                fee += inTier * tier.unitPriceCents();
            }
            lower = upper;
            if (upper == Long.MAX_VALUE) {
                break;
            }
        }
        return fee;
    }
}
