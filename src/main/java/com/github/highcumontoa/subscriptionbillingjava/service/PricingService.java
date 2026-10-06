package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.domain.OverageTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 计价与比例分摊（纯函数，无状态、无随机、无外部依赖，结果可复现）。
 *
 * <p>比例分摊规则：
 * <ul>
 *   <li>账期为 [periodStart, periodEnd)，时长 P 毫秒；套餐分段为 [segmentStart, segmentEnd)；</li>
 *   <li>取分段与账期交集 [lo, hi)，分摊比例 = (hi - lo) / P；</li>
 *   <li>比例月费 = 月费 * 比例，比例包含额度 = 整月包含单位 * 比例；</li>
 *   <li>所有除法按非负 HALF_UP 取整（金额单位：分；用量单位：整数）。</li>
 * </ul>
 *
 * <p>阶梯计价规则（分段累进，针对“超出包含额度后的第 1..N 个单位”）：
 * 第 u 个超用单位落入满足 from &lt; u &le; to 的档位（to 为 null 表示无上限），
 * 费用 = Σ 各档覆盖单位数 × 该档单价。
 */
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /** 非负整数四舍五入（HALF_UP）：round(num / denominator)。 */
    static long roundHalfUp(long num, long denominator) {
        return (2L * num + denominator) / (2L * denominator);
    }

    private long overlapStart(long segmentStart, long periodStart) {
        return Math.max(segmentStart, periodStart);
    }

    private long overlapEnd(long segmentEnd, long periodEnd) {
        return Math.min(segmentEnd, periodEnd);
    }

    public long proratedMonthlyPrice(long monthlyPriceCents,
                                     long segmentStart,
                                     long segmentEnd,
                                     long periodStart,
                                     long periodEnd) {
        long periodLen = periodEnd - periodStart;
        long lo = overlapStart(segmentStart, periodStart);
        long hi = overlapEnd(segmentEnd, periodEnd);
        long segLen = Math.max(0L, hi - lo);
        if (periodLen <= 0 || segLen <= 0) {
            return 0L;
        }
        long fee = roundHalfUp(monthlyPriceCents * segLen, periodLen);
        log.info("[pricing] proratedMonthlyPrice monthly={}c seg=[{},{}) period=[{},{}) "
                        + "segLen={} periodLen={} -> fee={}c",
                monthlyPriceCents, lo, hi, periodStart, periodEnd, segLen, periodLen, fee);
        return fee;
    }

    public long proratedIncludedUnits(long includedUnits,
                                      long segmentStart,
                                      long segmentEnd,
                                      long periodStart,
                                      long periodEnd) {
        long periodLen = periodEnd - periodStart;
        long lo = overlapStart(segmentStart, periodStart);
        long hi = overlapEnd(segmentEnd, periodEnd);
        long segLen = Math.max(0L, hi - lo);
        if (periodLen <= 0 || segLen <= 0) {
            return 0L;
        }
        long units = roundHalfUp(includedUnits * segLen, periodLen);
        log.info("[pricing] proratedIncludedUnits included={} segLen={}/{} -> units={}",
                includedUnits, segLen, periodLen, units);
        return units;
    }

    public long overageFee(List<OverageTier> tiers, long overageUnits) {
        if (tiers == null || tiers.isEmpty() || overageUnits <= 0) {
            return 0L;
        }
        long fee = 0L;
        for (OverageTier tier : tiers) {
            long upper = tier.toOverageUnitInclusive() == null
                    ? overageUnits
                    : Math.min(tier.toOverageUnitInclusive(), overageUnits);
            long covered = upper - tier.fromOverageUnitInclusive();
            if (covered > 0) {
                fee += covered * tier.unitPriceCents();
                log.info("[pricing] tier ({},{}] x {}c covered={} subtotal={}c",
                        tier.fromOverageUnitInclusive(), tier.toOverageUnitInclusive(),
                        tier.unitPriceCents(), covered, covered * tier.unitPriceCents());
            }
        }
        log.info("[pricing] overageFee overageUnits={} totalFee={}c", overageUnits, fee);
        return fee;
    }

    /**
     * 超用量在某档内的一段计价明细：区间 ({@code fromOverageUnitExclusive},
     * {@code toOverageUnitInclusive}] 共 {@code units} 个单位，按
     * {@code unitPriceCents} 计价得 {@code feeCents}（units × 单价，守恒可核对）。
     */
    public record TierSlice(long fromOverageUnitExclusive,
                            long toOverageUnitInclusive,
                            long unitPriceCents,
                            long units,
                            long feeCents) {
    }

    /**
     * 边际阶梯计价明细：把超用区间 ({@code fromOverageExclusive},
     * {@code toOverageInclusive}] 按阶梯切成若干段，逐段给出单位数与金额。
     *
     * <p>用于补差调整单的计价说明：已出账口径超用 from、补差后口径超用 to，
     * 新增超用 (to − from) 个单位落在哪些档、各档多少钱，切片金额之和恒等于
     * {@code overageFee(tiers, to) − overageFee(tiers, from)}（可复现核对）。
     */
    public List<TierSlice> overageTierSlices(List<OverageTier> tiers,
                                             long fromOverageExclusive,
                                             long toOverageInclusive) {
        List<TierSlice> slices = new java.util.ArrayList<>();
        if (tiers == null || tiers.isEmpty() || toOverageInclusive <= fromOverageExclusive) {
            log.info("[pricing] tierSlices empty range=({},{}]", fromOverageExclusive,
                    toOverageInclusive);
            return slices;
        }
        for (OverageTier tier : tiers) {
            long tierUpper = tier.toOverageUnitInclusive() == null
                    ? toOverageInclusive
                    : Math.min(tier.toOverageUnitInclusive(), toOverageInclusive);
            long lo = Math.max(tier.fromOverageUnitInclusive(), fromOverageExclusive);
            long covered = tierUpper - lo;
            if (covered > 0) {
                slices.add(new TierSlice(lo, tierUpper, tier.unitPriceCents(),
                        covered, covered * tier.unitPriceCents()));
                log.info("[pricing] tierSlice ({},{}] x {}c units={} subtotal={}c",
                        lo, tierUpper, tier.unitPriceCents(), covered,
                        covered * tier.unitPriceCents());
            }
        }
        long total = slices.stream().mapToLong(TierSlice::feeCents).sum();
        log.info("[pricing] tierSlices range=({},{}] slices={} marginalFee={}c",
                fromOverageExclusive, toOverageInclusive, slices.size(), total);
        return slices;
    }
}
