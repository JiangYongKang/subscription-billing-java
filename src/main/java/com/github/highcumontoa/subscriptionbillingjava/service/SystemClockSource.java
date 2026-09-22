package com.github.highcumontoa.subscriptionbillingjava.service;

public class SystemClockSource implements ClockSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
