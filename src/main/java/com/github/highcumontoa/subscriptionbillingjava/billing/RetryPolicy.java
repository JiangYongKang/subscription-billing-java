package com.github.highcumontoa.subscriptionbillingjava.billing;

import java.util.List;

/**
 * 可配置收款重试策略：
 * attempt 为 0 表示首次扣款；intervalsMillis[k-1] 为第 k 次失败后距下次重试的等待。
 * 重试次数达到 intervalsMillis.size() 仍失败 -> FAILED。
 */
public record RetryPolicy(List<Long> intervalsMillis) {

    public int maxAttempts() {
        return intervalsMillis.size() + 1;
    }
}
