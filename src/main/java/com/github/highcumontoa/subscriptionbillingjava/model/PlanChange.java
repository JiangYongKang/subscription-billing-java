package com.github.highcumontoa.subscriptionbillingjava.model;

import java.time.Instant;

/**
 * 套餐变更流水（不可变审计记录）。
 */
public record PlanChange(String id, String subscriptionId, ChangeType changeType,
                         String fromPlanCode, String toPlanCode,
                         Instant effectiveAt, long proratedDeltaCents,
                         long baseVersion, String note) {
}
