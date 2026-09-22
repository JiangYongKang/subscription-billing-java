package com.github.highcumontoa.subscriptionbillingjava.domain;

/**
 * 超阶梯的一档（针对“超出包含额度的用量”计数）：
 * fromOverageUnitInclusive <= 超出用量 <= toOverageUnitInclusive 时按 unitPriceCents 计价；
 * toOverageUnitInclusive 为 null 表示无上界。
 */
public record OverageTier(long fromOverageUnitInclusive,
                          Long toOverageUnitInclusive,
                          long unitPriceCents) {
}
