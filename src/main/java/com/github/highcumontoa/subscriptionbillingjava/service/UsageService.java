package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.error.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.model.SubscriptionStatus;
import com.github.highcumontoa.subscriptionbillingjava.model.UsageEvent;
import com.github.highcumontoa.subscriptionbillingjava.store.BillRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.SubscriptionRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.UsageRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.lock.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final UsageRepository usageRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BillRepository billRepository;
    private final LockManager lockManager;
    private final TimeService timeService;

    public UsageService(UsageRepository usageRepository,
                        SubscriptionRepository subscriptionRepository,
                        BillRepository billRepository,
                        LockManager lockManager,
                        TimeService timeService) {
        this.usageRepository = usageRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.billRepository = billRepository;
        this.lockManager = lockManager;
        this.timeService = timeService;
    }

    /**
     * 上报用量：
     * - eventId 全局幂等：重复上报返回 DUPLICATE_USAGE_EVENT（不会静默成功也不会重复计入）；
     * - 乱序/迟到事件按 occurredAt 归属账期，只要目标账期账单未确认即允许，聚合时统一重算；
     * - 已确认/已支付账期的补报返回 USAGE_LATE_PERIOD_CLOSED（可区分，不静默丢弃）。
     */
    public UsageEvent report(String eventId, String subscriptionId, long quantity,
                             Instant occurredAt) {
        if (eventId == null || eventId.isBlank()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "eventId 不能为空");
        }
        if (quantity <= 0) {
            throw new BillingException(ErrorCode.USAGE_EVENT_BAD_QUANTITY,
                    "用量必须为正数: quantity=" + quantity);
        }
        Subscription sub = subscriptionRepository.find(subscriptionId)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "订阅不存在: " + subscriptionId));
        Instant now = timeService.now();
        Instant occ = occurredAt == null ? now : occurredAt;
        if (occ.isAfter(now)) {
            throw new BillingException(ErrorCode.USAGE_EVENT_OUTSIDE_PERIOD,
                    "事件发生时间不能晚于当前时间: occurredAt=" + occ);
        }
        if (sub.getStatus() == SubscriptionStatus.CANCELLED && occ.isBefore(now)) {
            // 已取消订阅：仅允许取消时刻之前的补报，且仍受账期关闭校验约束。
        }
        long periodIndex = timeService.periodIndex(sub.getAnchor(), occ);
        Instant periodStart = timeService.periodStart(sub.getAnchor(), periodIndex);

        ReentrantLock lock = lockManager.get("sub:" + subscriptionId);
        lock.lock();
        try {
            // 在订阅锁内复查重复，保证并发同 eventId 只有一个写入。
            if (usageRepository.exists(eventId)) {
                log.warn("[用量拒绝-重复] eventId={} sub={} qty={} occurredAt={}",
                        eventId, subscriptionId, quantity, occ);
                throw new BillingException(ErrorCode.DUPLICATE_USAGE_EVENT,
                        "重复用量事件: eventId=" + eventId);
            }
            Bill bill = billRepository.findBySubscriptionAndPeriod(subscriptionId, periodStart)
                    .orElse(null);
            if (bill != null) {
                switch (bill.getStatus()) {
                    case CONFIRMED, PAID -> throw new BillingException(
                            ErrorCode.USAGE_LATE_PERIOD_CLOSED,
                            "账期账单已确认/支付，禁止补报: periodStart=" + periodStart
                                    + " billId=" + bill.getId() + " status=" + bill.getStatus());
                    case VOID -> throw new BillingException(ErrorCode.PERIOD_ALREADY_CLOSED,
                            "账期账单已作废: billId=" + bill.getId());
                    default -> { }
                }
            }

            UsageEvent event = new UsageEvent(eventId, subscriptionId, quantity, occ, now);
            if (!usageRepository.saveIfAbsent(event)) {
                throw new BillingException(ErrorCode.DUPLICATE_USAGE_EVENT,
                        "重复用量事件(并发): eventId=" + eventId);
            }
            log.info("[用量接收] eventId={} sub={} qty={} occurredAt={} 归属账期#{} start={}",
                    eventId, subscriptionId, quantity, occ, periodIndex, periodStart);
            return event;
        } finally {
            lock.unlock();
        }
    }
}
