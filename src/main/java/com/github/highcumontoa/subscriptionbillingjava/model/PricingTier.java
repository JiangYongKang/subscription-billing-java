package com.github.highcumontoa.subscriptionbillingjava.model;

/**
 * 阶梯定价的一个阶梯：超过 included 后，(prevIncluded, included] 区间单价 unitPriceCents/单位。
 * included 为 Long.MAX_VALUE 时表示末档。
 */
public record PricingTier(long included, long unitPriceCents) {
}
