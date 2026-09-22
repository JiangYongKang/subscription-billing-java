package com.github.highcumontoa.subscriptionbillingjava.model;

import java.time.Duration;
import java.util.List;

/**
 * 收款失败重试策略：maxAttempts 为最大尝试次数（含首次），backoff 为每次失败后的等待时长。
 * 超过最大次数后账单进入催收（DUNNING）。
 */
public record RetryPolicy(int maxAttempts, List<Duration> backoff) {
}
