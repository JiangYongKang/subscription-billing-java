package com.github.highcumontoa.subscriptionbillingjava.repo;

import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.usage.UsageEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用量事件本地存储。
 * <ul><li>eventId 全局唯一：{@link #save} 通过 putIfAbsent 原子去重，
 * 重复上报抛 DUPLICATE_EVENT（而非静默丢弃）；</li>
 * <li>窗口查询按 (occurredAt, eventId) 稳定排序，乱序/迟到事件的聚合口径可复现。</li></ul>
 */
public class UsageEventRepository {

    private final Map<String, UsageEvent> byEventId = new ConcurrentHashMap<>();

    public boolean exists(String eventId) {
        return byEventId.containsKey(eventId);
    }

    public UsageEvent findByEventId(String eventId) {
        return byEventId.get(eventId);
    }

    public void save(UsageEvent event) {
        UsageEvent existing = byEventId.putIfAbsent(event.eventId(), event);
        if (existing != null) {
            throw new BillingException(ErrorCode.DUPLICATE_EVENT,
                    "用量事件重复上报被拒绝: eventId=" + event.eventId()
                            + ", 已存 subscriptionId=" + existing.subscriptionId()
                            + " quantity=" + existing.quantity()
                            + " occurredAt=" + existing.occurredAt());
        }
    }

    public List<UsageEvent> findBySubscriptionAndOccurredBetween(String subscriptionId,
                                                                 long fromInclusive,
                                                                 long toExclusive) {
        return byEventId.values().stream()
                .filter(e -> e.subscriptionId().equals(subscriptionId))
                .filter(e -> e.occurredAt() >= fromInclusive && e.occurredAt() < toExclusive)
                .sorted(Comparator.comparingLong(UsageEvent::occurredAt)
                        .thenComparing(UsageEvent::eventId))
                .toList();
    }
}
