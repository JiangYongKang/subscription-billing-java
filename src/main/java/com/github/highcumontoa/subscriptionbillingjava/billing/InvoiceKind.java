package com.github.highcumontoa.subscriptionbillingjava.billing;

/**
 * 账单类型。
 * <ul>
 *   <li>{@link #REGULAR}：账期正常出账，一个订阅的一个账期至多一张；</li>
 *   <li>{@link #ADJUSTMENT}：账期结清（PAID）后迟到补报用量产生的补差调整单，
 *       通过 linkedInvoiceId 明确关联原账单，可像普通账单一样收款、查询与对账。</li>
 * </ul>
 */
public enum InvoiceKind {
    REGULAR,
    ADJUSTMENT
}
