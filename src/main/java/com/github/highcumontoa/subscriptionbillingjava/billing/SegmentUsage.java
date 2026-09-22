package com.github.highcumontoa.subscriptionbillingjava.billing;

import java.time.Instant;

/**
 * 单个套餐片段的用量聚合结果。
 */
public record SegmentUsage(String planCode, Instant start, Instant end,
                           long quantity, long proratedQuota,
                           long overageQuantity, long baseFeeCents,
                           long overageFeeCents) {
}
