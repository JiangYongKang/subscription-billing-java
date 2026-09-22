package com.github.highcumontoa.subscriptionbillingjava.bootstrap;

import com.github.highcumontoa.subscriptionbillingjava.service.ClockSource;

/** 测试用可控时钟：可固定、可显式推进，保证重试/迟到场景确定可复现。 */
public class ManualClock implements ClockSource {

    private volatile long now;

    public ManualClock(long now) {
        this.now = now;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void set(long now) {
        this.now = now;
    }

    public void advance(long deltaMillis) {
        this.now += deltaMillis;
    }
}
