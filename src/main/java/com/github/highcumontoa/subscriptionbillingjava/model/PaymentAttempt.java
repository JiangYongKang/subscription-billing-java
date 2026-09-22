package com.github.highcumontoa.subscriptionbillingjava.model;

import java.time.Instant;

public record PaymentAttempt(int attemptNo, Instant at, boolean success,
                             String failureCode, String idempotencyKey) {
}
