package com.github.highcumontoa.subscriptionbillingjava.billing;

import java.util.ArrayList;
import java.util.List;

/**
 * 账单：一个订阅的一个账期（或补报调整）对应一张账单。
 * planFeeCents 为各套餐分段比例费用之和；usageFeeCents 为阶梯超用费用；
 * total = planFee + usageFee，生成后冻结（总额守恒）。
 */
public final class Invoice {

    /** 账单中一个套餐分段的可核对明细。 */
    public record LineItem(String planCode, long start, long end,
                           long feeCents, long includedUnits) {
    }


    private String id;
    private String subscriptionId;
    private long periodStart;
    private long periodEnd;
    private long generatedAt;
    private long planFeeCents;
    private long usageFeeCents;
    private long totalCents;
    private InvoiceStatus status;
    private int attempt;
    private long nextRetryAt;
    private List<LineItem> lineItems = new ArrayList<>();
    private List<Long> attemptAt = new ArrayList<>();
    private List<String> attemptResults = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public long getPeriodStart() { return periodStart; }
    public void setPeriodStart(long periodStart) { this.periodStart = periodStart; }
    public long getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(long periodEnd) { this.periodEnd = periodEnd; }
    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
    public long getPlanFeeCents() { return planFeeCents; }
    public void setPlanFeeCents(long planFeeCents) { this.planFeeCents = planFeeCents; }
    public long getUsageFeeCents() { return usageFeeCents; }
    public void setUsageFeeCents(long usageFeeCents) { this.usageFeeCents = usageFeeCents; }
    public long getTotalCents() { return totalCents; }
    public void setTotalCents(long totalCents) { this.totalCents = totalCents; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public long getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(long nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public List<Long> getAttemptAt() { return attemptAt; }
    public List<String> getAttemptResults() { return attemptResults; }
    public List<LineItem> getLineItems() { return lineItems; }
}
