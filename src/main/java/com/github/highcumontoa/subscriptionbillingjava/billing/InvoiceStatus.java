package com.github.highcumontoa.subscriptionbillingjava.billing;

public enum InvoiceStatus {
    /** 已生成，等待收款确认 */
    PENDING,
    /** 收款成功并最终确认（终态，幂等） */
    PAID,
    /** 收款失败重试中（催收流程） */
    DUNNING,
    /** 重试策略耗尽（终态） */
    FAILED
}
