package com.github.highcumontoa.subscriptionbillingjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅。账期锚点 anchor 为订阅创建时间，每个账期 = [anchor+n月, anchor+(n+1)月)。
 */
public class Subscription {
    private final String id;
    private String planCode;
    private SubscriptionStatus status;
    private final Instant anchor;
    private final Instant createdAt;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private final List<Segment> segments = new ArrayList<>();
    private long version;

    public Subscription(String id, String planCode, Instant anchor, Instant createdAt) {
        this.id = id;
        this.planCode = planCode;
        this.anchor = anchor;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }
    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }
    public Instant getAnchor() { return anchor; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant v) { this.currentPeriodStart = v; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant v) { this.currentPeriodEnd = v; }
    public List<Segment> getSegments() { return segments; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
