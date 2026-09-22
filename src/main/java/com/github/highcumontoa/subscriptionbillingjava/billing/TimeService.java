package com.github.highcumontoa.subscriptionbillingjava.billing;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 时间服务：统一时钟来源，测试可注入固定时钟以保证可复现。账期按 UTC 月对齐，
 * 账期 n = [anchor+n月, anchor+(n+1)月)。
 */
@Component
public class TimeService {

    private Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** 测试注入固定/可控时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void resetClock() {
        this.clock = Clock.systemUTC();
    }

    public Instant plusMonths(Instant t, long months) {
        return ZonedDateTime.ofInstant(t, ZoneOffset.UTC)
                .plusMonths(months)
                .toInstant();
    }

    /**
     * 计算 t 相对锚点 anchor 的账期序号 n（满足 anchor+n月 <= t < anchor+(n+1)月）。
     */
    public long periodIndex(Instant anchor, Instant t) {
        ZonedDateTime za = ZonedDateTime.ofInstant(anchor, ZoneOffset.UTC);
        ZonedDateTime zt = ZonedDateTime.ofInstant(t, ZoneOffset.UTC);
        long n = (long) (zt.getYear() - za.getYear()) * 12
                + (zt.getMonthValue() - za.getMonthValue());
        // 锚点为月末等场景下 plusMonths 会有日期钳制，用边界比较修正 +/-1。
        Instant start = plusMonths(anchor, n);
        if (start.isAfter(t)) {
            n--;
        } else if (!plusMonths(anchor, n + 1).isAfter(t)) {
            n++;
        }
        return n;
    }

    public Instant periodStart(Instant anchor, long index) {
        return plusMonths(anchor, index);
    }

    public Instant periodEnd(Instant anchor, long index) {
        return plusMonths(anchor, index + 1);
    }
}
