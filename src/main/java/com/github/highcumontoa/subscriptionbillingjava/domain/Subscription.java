package com.github.highcumontoa.subscriptionbillingjava.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 订阅聚合根。periodStart/periodEnd 为当前账期 [start,end)；
 * version 为乐观锁版本号，并发变更通过 CAS 防止丢失更新与重复计费。
 */
public final class Subscription {

    private String id;
    private SubscriptionStatus status;
    private long createdAt;
    private long periodStart;
    private long periodEnd;
    private long cancelledAt;
    private long version;
    private final List<PlanSegment> segments = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getPeriodStart() { return periodStart; }
    public void setPeriodStart(long periodStart) { this.periodStart = periodStart; }
    public long getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(long periodEnd) { this.periodEnd = periodEnd; }
    public long getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(long cancelledAt) { this.cancelledAt = cancelledAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public List<PlanSegment> getSegments() { return segments; }
}
