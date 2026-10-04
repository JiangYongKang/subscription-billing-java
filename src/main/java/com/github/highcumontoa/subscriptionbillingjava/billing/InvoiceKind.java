package com.github.highcumontoa.subscriptionbillingjava.billing;

/**
 * 账单类型。
 * <ul><li>REGULAR：账期的原始账单（一个订阅的一个账期至多一张）；</li>
 * <li>ADJUSTMENT：账期结清后迟到用量补报形成的补差调整单，
 * 通过 linkedInvoiceId 关联原账单，可独立收款、查询与对账。</li></ul>
 */
public enum InvoiceKind {
    REGULAR,
    ADJUSTMENT
}
