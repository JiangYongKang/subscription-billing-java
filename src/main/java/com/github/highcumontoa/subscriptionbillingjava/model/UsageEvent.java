package com.github.highcumontoa.subscriptionbillingjava.model;

import java.time.Instant;

/**
 * 用量事件。eventId 全局唯一，用于幂等去重。
 */
public record UsageEvent(String eventId, String subscriptionId, long quantity,
                         Instant occurredAt, Instant receivedAt) {
}
