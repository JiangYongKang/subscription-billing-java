package com.github.highcumontoa.subscriptionbillingjava.repo;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 账单本地存储。
 * <ul><li>(subscriptionId, periodStart) 唯一索引只覆盖 REGULAR 账单：账单生成在订阅锁 +
 * 唯一索引检查下进行，同一账期重复出账会被拒绝（INVOICE_PERIOD_CONFLICT）；</li>
 * <li>ADJUSTMENT 补差调整单不占用账期唯一索引（同一账期可有多张），
 * 通过 {@link #findAdjustments} 按账期查询；</li>
 * <li>与 SubscriptionRepository 共享“按订阅 ID 取锁”的语义：本仓储独立持锁，
 * 账单操作不会与订阅变更形成交叉死锁（仓储间不存在嵌套加锁顺序）。</li></ul>
 */
public class InvoiceRepository {

    private final Map<String, Invoice> byId = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, String>> periodIndex = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public Invoice save(Invoice invoice) {
        byId.put(invoice.getId(), invoice);
        if (invoice.getKind() == InvoiceKind.REGULAR) {
            periodIndex.computeIfAbsent(invoice.getSubscriptionId(), k -> new ConcurrentHashMap<>())
                    .putIfAbsent(invoice.getPeriodStart(), invoice.getId());
        }
        return invoice;
    }

    public Invoice findById(String id) {
        return byId.get(id);
    }

    public Invoice requireById(String id) {
        Invoice invoice = byId.get(id);
        if (invoice == null) {
            throw new com.github.highcumontoa.subscriptionbillingjava.exception.BillingException(
                    com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode.INVOICE_NOT_FOUND,
                    "账单不存在: " + id);
        }
        return invoice;
    }

    public Optional<Invoice> findBySubscriptionAndPeriod(String subscriptionId, long periodStart) {
        Map<Long, String> idx = periodIndex.get(subscriptionId);
        if (idx == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(idx.get(periodStart)).map(byId::get);
    }

    public List<Invoice> findBySubscription(String subscriptionId) {
        return byId.values().stream()
                .filter(i -> i.getSubscriptionId().equals(subscriptionId))
                .sorted(java.util.Comparator.comparingLong(Invoice::getPeriodStart)
                        .thenComparing(Invoice::getId))
                .toList();
    }

    /** 某账期下已产生的补差调整单（按生成顺序，可复现）。 */
    public List<Invoice> findAdjustments(String subscriptionId, long periodStart) {
        return byId.values().stream()
                .filter(i -> i.getSubscriptionId().equals(subscriptionId))
                .filter(i -> i.getKind() == InvoiceKind.ADJUSTMENT)
                .filter(i -> i.getPeriodStart() == periodStart)
                .sorted(java.util.Comparator.comparingLong(Invoice::getGeneratedAt)
                        .thenComparing(Invoice::getId))
                .toList();
    }

    public <T> T withLock(String subscriptionId, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(subscriptionId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
