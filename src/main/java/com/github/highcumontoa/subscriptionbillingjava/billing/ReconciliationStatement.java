package com.github.highcumontoa.subscriptionbillingjava.billing;

import java.util.List;

/**
 * 对账结论：对任一订阅的任一已出账账期，
 * “原账单金额 + 已产生的补差金额 = 当前口径下的应收总额”，差额必须为零且可复现。
 *
 * @param subscriptionId       订阅 ID
 * @param periodStart          账期起点（毫秒）
 * @param periodEnd            账期终点（毫秒，左闭右开）
 * @param originalInvoiceId    原账单 ID
 * @param originalTotalCents   原账单当前金额（未结清账期可能已被重算更新）
 * @param adjustmentInvoiceIds 已产生的补差调整单 ID（按生成顺序）
 * @param adjustmentTotalCents 补差金额合计
 * @param recomputedTotalCents 按当前已存事件重新汇总的应收总额
 * @param expectedTotalCents   原账单金额 + 补差金额合计
 * @param discrepancyCents     差额 = recomputed - expected，必须为零
 * @param basisEventCount      当前口径下参与汇总的事件数
 */
public record ReconciliationStatement(String subscriptionId,
                                      long periodStart,
                                      long periodEnd,
                                      String originalInvoiceId,
                                      long originalTotalCents,
                                      List<String> adjustmentInvoiceIds,
                                      long adjustmentTotalCents,
                                      long recomputedTotalCents,
                                      long expectedTotalCents,
                                      long discrepancyCents,
                                      int basisEventCount) {

    public boolean balanced() {
        return discrepancyCents == 0L;
    }
}
