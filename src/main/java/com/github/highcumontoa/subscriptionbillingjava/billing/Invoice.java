package com.github.highcumontoa.subscriptionbillingjava.billing;

import com.github.highcumontoa.subscriptionbillingjava.service.PricingService;

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
     * 仅 ADJUSTMENT：本次补差覆盖的一笔迟到补报记录
     * （事件 id、用量、实际发生时间），业务可逐笔核对来源。
     */
    public record SourceEventUsage(String eventId, long quantity, long occurredAt) {
    }

    /**
     * 仅 ADJUSTMENT：补差金额的计价说明（按什么规则把新增用量算成了多少钱）。
     *
     * <p>口径：新增用量先抵扣账期包含额度的剩余部分，超出部分按计费套餐的
     * 阶梯单价边际累进计价；{@code tierSlices} 逐段给出新增超用落在各档的
     * 单位数与金额，切片金额之和恒等于 {@code deltaCents}（= 本单补差金额）。
     *
     * @param planCode         计费套餐（账期末开口段套餐，与原账单口径一致）
     * @param includedUnits    账期包含额度（原账单冻结值）
     * @param usedUnitsBefore  补差前已出账口径的总用量
     * @param usedUnitsAfter   补差后口径的总用量
     * @param newUnits         本次新增用量（= usedUnitsAfter − usedUnitsBefore，
     *                         也等于 sourceEventUsages 各笔 quantity 之和）
     * @param overageBefore    补差前已出账口径的超用量
     * @param overageAfter     补差后口径的超用量
     * @param tierSlices       新增超用区间 (overageBefore, overageAfter] 的阶梯切片
     * @param deltaCents       补差金额（= Σ tierSlices.feeCents）
     */
    public record AdjustmentPricing(String planCode,
                                    long includedUnits,
                                    long usedUnitsBefore,
                                    long usedUnitsAfter,
                                    long newUnits,
                                    long overageBefore,
                                    long overageAfter,
                                    List<PricingService.TierSlice> tierSlices,
                                    long deltaCents) {
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
    /** 仅 ADJUSTMENT：本次补差覆盖的迟到补报逐笔明细（事件、用量、发生时间）。 */
    private List<SourceEventUsage> sourceEventUsages = new ArrayList<>();
    /** 仅 ADJUSTMENT：本次补差新增用量总量（= sourceEventUsages 各笔之和）。 */
    private long newUnitsTotal;
    /** 仅 ADJUSTMENT：补差金额的计价说明（新增用量按什么规则算成了多少钱）。 */
    private AdjustmentPricing adjustmentPricing;
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
    public List<SourceEventUsage> getSourceEventUsages() { return sourceEventUsages; }
    public long getNewUnitsTotal() { return newUnitsTotal; }
    public void setNewUnitsTotal(long newUnitsTotal) { this.newUnitsTotal = newUnitsTotal; }
    public AdjustmentPricing getAdjustmentPricing() { return adjustmentPricing; }
    public void setAdjustmentPricing(AdjustmentPricing adjustmentPricing) {
        this.adjustmentPricing = adjustmentPricing;
    }
    public List<String> getBilledEventIds() { return billedEventIds; }
    public List<RecalcAudit> getRecalcAudits() { return recalcAudits; }
}
