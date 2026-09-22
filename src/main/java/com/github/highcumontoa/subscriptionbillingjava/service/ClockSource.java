package com.github.highcumontoa.subscriptionbillingjava.service;

/** 可注入时钟，测试可固定/推进时间以复现重试与迟到场景。 */
public interface ClockSource {
    long nowMillis();
}
