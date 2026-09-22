package com.github.highcumontoa.subscriptionbillingjava.billing;

import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地模拟支付网关：不访问任何外部资源、不需要真实凭据。
 *
 * <p>行为可编排：测试通过 {@link #prime} 为某张账单预设逐次扣款结果
 * （成功/失败 + 原因）；未预设时默认成功。每次扣款无论成功失败都留下
 * 不可变记录 {@link ChargeRecord}，供测试断言“同一账单只被实际扣款一次”。
 */
public class LocalPaymentGateway {

    public static final class ChargeResult {
        public final boolean success;
        public final String reference;
        public final String reason;

        public ChargeResult(boolean success, String reference, String reason) {
            this.success = success;
            this.reference = reference;
            this.reason = reason;
        }
    }

    /** 一次真实扣款尝试的审计记录。 */
    public static final class ChargeRecord {
        public final String invoiceId;
        public final int attempt;
        public final long amountCents;
        public final boolean success;
        public final String reference;
        public final String reason;

        public ChargeRecord(String invoiceId, int attempt, long amountCents,
                            boolean success, String reference, String reason) {
            this.invoiceId = invoiceId;
            this.attempt = attempt;
            this.amountCents = amountCents;
            this.success = success;
            this.reference = reference;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "ChargeRecord{invoice=" + invoiceId + ", attempt=" + attempt
                    + ", amount=" + amountCents + "c, success=" + success
                    + ", ref=" + reference + ", reason='" + reason + "'}";
        }
    }

    private final Map<String, Deque<ChargeResult>> scripts = new ConcurrentHashMap<>();
    private final List<ChargeRecord> records = java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * 为账单预设扣款脚本：每消费一条对应一次扣款；脚本耗尽后重复最后一条结果。
     */
    public void prime(String invoiceId, boolean success, String reason) {
        scripts.computeIfAbsent(invoiceId, k -> new ArrayDeque<>())
                .add(new ChargeResult(success, null, reason));
    }

    /**
     * 执行一次扣款。attempt 为账单上记录的尝试序号（首次为 0）。
     */
    public ChargeResult charge(Invoice invoice, int attempt) {
        if (invoice.getTotalCents() < 0) {
            throw new BillingException(ErrorCode.PAYMENT_DECLINED,
                    "扣款金额非法为负: " + invoice.getTotalCents());
        }
        Deque<ChargeResult> script = scripts.get(invoice.getId());
        ChargeResult planned;
        if (script == null || script.isEmpty()) {
            planned = new ChargeResult(true, null, "OK_DEFAULT");
        } else {
            planned = script.pollFirst();
            if (script.isEmpty()) {
                // 耗尽后保持最后结论，使“持续失败直到催收放弃”场景可复现
                script.addLast(planned);
            }
        }
        String reference = "CHG-" + invoice.getId() + "-" + attempt;
        ChargeRecord record = new ChargeRecord(invoice.getId(), attempt, invoice.getTotalCents(),
                planned.success, reference, planned.reason);
        records.add(record);
        System.out.println("[gateway] charge input={invoice=" + invoice.getId()
                + ", attempt=" + attempt + ", amount=" + invoice.getTotalCents()
                + "c} decision=success:" + planned.success
                + " reason=" + planned.reason + " ref=" + reference);
        return new ChargeResult(planned.success, reference, planned.reason);
    }

    public List<ChargeRecord> recordsOf(String invoiceId) {
        synchronized (records) {
            return records.stream().filter(r -> r.invoiceId.equals(invoiceId)).toList();
        }
    }

    public int totalChargeCount() {
        return records.size();
    }
}
