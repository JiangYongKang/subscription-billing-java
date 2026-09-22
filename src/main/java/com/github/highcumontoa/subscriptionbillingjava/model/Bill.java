package com.github.highcumontoa.subscriptionbillingjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个订阅一个账期一张账单（按 subscriptionId+periodStart 唯一）。
 * DRAFT 可反复重算；CONFIRMED 后收款；成功为 PAID；失败进入重试/催收。
 */
public class Bill {
    private final String id;
    private final String subscriptionId;
    private final Instant periodStart;
    private final Instant periodEnd;
    private final List<BillLineItem> lineItems = new ArrayList<>();
    private long totalCents;
    private BillStatus status;
    private final List<PaymentAttempt> attempts = new ArrayList<>();
    private String confirmedIdempotencyKey;
    private Instant confirmedAt;
    private Instant nextRetryAt;
    private int retryCount;
    private String dunningNote;

    public Bill(String id, String subscriptionId, Instant periodStart, Instant periodEnd) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    public String getId() { return id; }
    public String getSubscriptionId() { return subscriptionId; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public List<BillLineItem> getLineItems() { return lineItems; }
    public long getTotalCents() { return totalCents; }
    public void setTotalCents(long totalCents) { this.totalCents = totalCents; }
    public BillStatus getStatus() { return status; }
    public void setStatus(BillStatus status) { this.status = status; }
    public List<PaymentAttempt> getAttempts() { return attempts; }
    public String getConfirmedIdempotencyKey() { return confirmedIdempotencyKey; }
    public void setConfirmedIdempotencyKey(String k) { this.confirmedIdempotencyKey = k; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getDunningNote() { return dunningNote; }
    public void setDunningNote(String dunningNote) { this.dunningNote = dunningNote; }
}
