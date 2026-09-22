package com.github.highcumontoa.subscriptionbillingjava.model;

import java.util.List;

/**
 * 套餐定义。amountCents 为一个完整账期（月）的基础价；
 * quotaIncluded 为账期内含量；overageTiers 为超额部分的阶梯单价（按分段比例配额计价）。
 */
public record Plan(String code, String name, long amountCents, long quotaIncluded,
                   List<PricingTier> overageTiers) {
}
