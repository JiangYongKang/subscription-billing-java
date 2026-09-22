package com.github.highcumontoa.subscriptionbillingjava.domain;

/**
 * 账期内的套餐分段：[start, end) 内订阅处于 planCode；end 为 null 表示开口段。
 * 周期内升降级通过“关闭旧段 + 开新段”表达，比例费用按分段时长可复现计算。
 */
public record PlanSegment(String planCode, long start, Long end) {
}
