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

    /**
     * 未结清账单因迟到补报触发的一次重算审计：记录重算时刻、触发事件、
     * 重算前后金额与当时的完整计费依据事件集合（差异可逐个事件核对）。
     */
    public record RecalcAudit(long at, String triggerEventId,
                              long oldUsageFeeCents, long newUsageFeeCents,
                              long oldTotalCents, long newTotalCents,
                              List<String> basisEventIds) {
    }

    /**
     * 补差调整单中一条来源补报记录的逐笔明细：事件 id、发生时间、新增用量，
     * 以及该笔记录在本次补差中贡献的费用（按发生时间排序后逐笔边际计价，
     * 各笔 feeCents 之和恒等于调整单总额）。
     */
    public record AdjustmentLine(String eventId, long occurredAt,
                                 long quantity, long feeCents) {
    }

    /**
     * 补差计价的一段阶梯切片：本次新增超用量落在
     * (fromOverageUnitExclusive, toOverageUnitInclusive] 区间的 units 个单位，
     * 按 unitPriceCents 单价计得 feeCents。各切片 units 之和 = 新增超用量，
     * feeCents 之和 = 调整单总额，计价规则可逐段核对。
     */
    public record PricingSlice(long fromOverageUnitExclusive,
                               long toOverageUnitInclusive,
                               long units, long unitPriceCents, long feeCents) {
    }

    /**
     * 补差调整单的完整核对明细（仅 ADJUSTMENT 账单携带）：
     * <ul>
     *   <li>addedUnits：本次补报新增的总用量；</li>
     *   <li>overageBeforeUnits/overageAfterUnits：补报前/后的账期超用量
     *       （相对原账单冻结的包含额度）；</li>
     *   <li>pricingSlices：新增超用量按账期计费套餐阶梯展开的计价切片；</li>
     *   <li>lines：逐笔来源补报记录及其费用贡献。</li>
     * </ul>
     * 守恒不变量：Σ lines.quantity = addedUnits；Σ lines.feeCents
     * = Σ pricingSlices.feeCents = 调整单 totalCents。
     */
    public record AdjustmentDetail(String planCode, long addedUnits,
                                   long overageBeforeUnits, long overageAfterUnits,
                                   List<PricingSlice> pricingSlices,
                                   List<AdjustmentLine> lines) {
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
    private InvoiceKind kind = InvoiceKind.REGULAR;
    /** 仅 ADJUSTMENT：关联的原账单 id。 */
    private String linkedInvoiceId;
    /** 仅 ADJUSTMENT：本次补差覆盖的迟到补报事件 id（可逐个核对来源）。 */
    private List<String> sourceEventIds = new ArrayList<>();
    /** 仅 ADJUSTMENT：新增用量、计价规则切片与逐笔来源明细（业务对账依据）。 */
    private AdjustmentDetail adjustmentDetail;
    /** 仅 REGULAR：生成/最近一次重算时已计入账单的事件 id（补差覆盖判定的依据）。 */
    private List<String> billedEventIds = new ArrayList<>();
    /** 仅 REGULAR 未结清期间：迟到补报触发的重算审计轨迹。 */
    private List<RecalcAudit> recalcAudits = new ArrayList<>();

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
    public InvoiceKind getKind() { return kind; }
    public void setKind(InvoiceKind kind) { this.kind = kind; }
    public String getLinkedInvoiceId() { return linkedInvoiceId; }
    public void setLinkedInvoiceId(String linkedInvoiceId) { this.linkedInvoiceId = linkedInvoiceId; }
    public List<String> getSourceEventIds() { return sourceEventIds; }
    public AdjustmentDetail getAdjustmentDetail() { return adjustmentDetail; }
    public void setAdjustmentDetail(AdjustmentDetail adjustmentDetail) {
        this.adjustmentDetail = adjustmentDetail;
    }
    public List<String> getBilledEventIds() { return billedEventIds; }
    public List<RecalcAudit> getRecalcAudits() { return recalcAudits; }
}
