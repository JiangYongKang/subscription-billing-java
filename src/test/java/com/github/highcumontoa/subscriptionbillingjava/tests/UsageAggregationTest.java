package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.ProrationCalculator;
import com.github.highcumontoa.subscriptionbillingjava.billing.SegmentUsage;
import com.github.highcumontoa.subscriptionbillingjava.billing.UsageAggregator;
import com.github.highcumontoa.subscriptionbillingjava.model.ChangeType;
import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import com.github.highcumontoa.subscriptionbillingjava.model.PricingTier;
import com.github.highcumontoa.subscriptionbillingjava.model.Segment;
import com.github.highcumontoa.subscriptionbillingjava.model.UsageEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用量聚合语义：事件按发生时间归属片段；片段配额按时长比例缩放（向下取整）；
 * 乱序与迟到事件不改变聚合结果（稳定可复现）。
 */
class UsageAggregationTest {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregationTest.class);

    private final UsageAggregator aggregator = new UsageAggregator(new ProrationCalculator());

    private final Instant pStart = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant pEnd = Instant.parse("2026-02-01T00:00:00Z");
    private final Instant mid = Instant.parse("2026-01-16T12:00:00Z");

    private Plan basic() {
        return new Plan("BASIC", "基础版", 1000, 1000, List.of(
                new PricingTier(1000, 3),
                new PricingTier(5000, 2),
                new PricingTier(Long.MAX_VALUE, 1)));
    }

    private Plan pro() {
        return new Plan("PRO", "专业版", 5000, 5000, List.of(
                new PricingTier(5000, 2),
                new PricingTier(Long.MAX_VALUE, 1)));
    }

    @Test
    void aggregate_eventsFallIntoSegments_byOccurredTime() {
        List<Segment> segments = List.of(
                new Segment("BASIC", pStart, mid, null),
                new Segment("PRO", mid, pEnd, ChangeType.UPGRADE));
        // 乱序提交：先给后段、再给前段；另加一条“迟到”但仍在账期内的事件。
        List<UsageEvent> events = List.of(
                new UsageEvent("e2", "s1", 2000, mid.plusSeconds(3600), pEnd),
                new UsageEvent("late1", "s1", 300, pStart.plusSeconds(60), pEnd.plusSeconds(999)),
                new UsageEvent("e1", "s1", 400, pStart.plusSeconds(120), mid));

        List<SegmentUsage> r1 = aggregator.aggregate(events, segments,
                Map.of("BASIC", basic(), "PRO", pro()), pStart, pEnd);
        // 换成按发生时间排序的入参，结果必须完全一致
        List<UsageEvent> sorted = events.stream()
                .sorted(java.util.Comparator.comparing(UsageEvent::occurredAt)).toList();
        List<SegmentUsage> r2 = aggregator.aggregate(sorted, segments,
                Map.of("BASIC", basic(), "PRO", pro()), pStart, pEnd);

        log.info("[输入] 乱序/迟到事件={} -> 片段0 BASIC qty={} quota={} overage={} baseFee={} overFee={}",
                events.size(), r1.get(0).quantity(), r1.get(0).proratedQuota(),
                r1.get(0).overageQuantity(), r1.get(0).baseFeeCents(), r1.get(0).overageFeeCents());
        log.info("[判定] 片段1 PRO qty={} quota={} overage={} baseFee={} overFee={}",
                r1.get(1).quantity(), r1.get(1).proratedQuota(),
                r1.get(1).overageQuantity(), r1.get(1).baseFeeCents(), r1.get(1).overageFeeCents());

        assertEquals(700, r1.get(0).quantity());      // 400 + 300 迟到事件归前段
        assertEquals(2000, r1.get(1).quantity());
        // 配额按片段时长缩放：1000 * 15.5/31 = 500
        assertEquals(500, r1.get(0).proratedQuota());
        // 5000 * 15.5/31 = 2500
        assertEquals(2500, r1.get(1).proratedQuota());
        assertEquals(200, r1.get(0).overageQuantity()); // 700-500
        assertEquals(0, r1.get(1).overageQuantity());   // 2000 <= 2500
        assertEquals(200 * 3, r1.get(0).overageFeeCents());

        // 乱序 vs 有序：稳定
        assertEquals(r1.toString(), r2.toString());
    }

    @Test
    void aggregate_fullPeriodSegment_baseFeeEqualsPlanPrice() {
        List<Segment> segments = List.of(new Segment("BASIC", pStart, pEnd, null));
        List<SegmentUsage> r = aggregator.aggregate(List.of(), segments,
                Map.of("BASIC", basic()), pStart, pEnd);
        log.info("[输入] 无变更整账期 BASIC -> [判定] baseFee=1000 quota=1000, 实际 baseFee={} quota={}",
                r.get(0).baseFeeCents(), r.get(0).proratedQuota());
        assertEquals(1000, r.get(0).baseFeeCents());
        assertEquals(1000, r.get(0).proratedQuota());
    }
}
