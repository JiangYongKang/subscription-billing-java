package com.github.highcumontoa.subscriptionbillingjava.model;

import java.time.Instant;

/**
 * 账期内一个套餐片段（因账期内变更产生）。
 */
public record Segment(String planCode, Instant start, Instant end, ChangeType reason) {
}
