package com.github.highcumontoa.subscriptionbillingjava.domain;

import java.util.List;

/**
 * 套餐定义（本地目录数据）。
 * monthlyPriceCents 为整月价格（分）；includedUnits 为整月包含用量；
 * overageTiers 为超出包含额度后的阶梯单价（分/单位）。
 */
public record Plan(String code,
                   String name,
                   long monthlyPriceCents,
                   long includedUnits,
                   List<OverageTier> overageTiers) {
}
