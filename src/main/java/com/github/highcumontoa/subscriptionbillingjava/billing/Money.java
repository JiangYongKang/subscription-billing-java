package com.github.highcumontoa.subscriptionbillingjava.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具：内部一律使用 long 分；比例计算用 BigDecimal，HALF_UP 到分。
 */
public final class Money {

    private Money() {
    }

    /**
     * 按时间比例分摊：amountCents × partSeconds / totalSeconds，HALF_UP 到分。
     */
    public static long prorate(long amountCents, long partSeconds, long totalSeconds) {
        if (totalSeconds <= 0 || partSeconds <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(partSeconds))
                .divide(BigDecimal.valueOf(totalSeconds), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** 返回 [0,1] 的时间比例（用于按比例缩放配额）。 */
    public static BigDecimal ratio(long partSeconds, long totalSeconds) {
        if (totalSeconds <= 0) {
            return BigDecimal.ZERO;
        }
        long p = Math.max(0, Math.min(partSeconds, totalSeconds));
        return BigDecimal.valueOf(p).divide(BigDecimal.valueOf(totalSeconds), 10, RoundingMode.DOWN);
    }
}
