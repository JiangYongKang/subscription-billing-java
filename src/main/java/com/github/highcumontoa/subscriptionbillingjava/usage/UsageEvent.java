package com.github.highcumontoa.subscriptionbillingjava.usage;

/**
 * 用量事件（只增不改）。occurredAt 为用量实际发生时间；reportedAt 为上报时间。
 * eventId 为幂等键：相同 eventId 重复上报只计一次（返回 DUPLICATE_EVENT 区分拒绝）。
 */
public record UsageEvent(String eventId,
                         String subscriptionId,
                         long quantity,
                         long occurredAt,
                         long reportedAt) {
}
