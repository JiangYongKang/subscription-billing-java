package com.github.highcumontoa.subscriptionbillingjava.domain;

/**
 * 套餐变更审计记录（按订阅时间序追加，只增不改）。
 */
public record PlanChange(String id,
                         String subscriptionId,
                         PlanChangeType type,
                         String fromPlanCode,
                         String toPlanCode,
                         long effectiveAt,
                         long version) {
}
