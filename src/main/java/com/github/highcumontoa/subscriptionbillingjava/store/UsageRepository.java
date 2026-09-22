package com.github.highcumontoa.subscriptionbillingjava.store;

import com.github.highcumontoa.subscriptionbillingjava.model.UsageEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class UsageRepository {

    private final Map<String, UsageEvent> byEventId = new ConcurrentHashMap<>();
    private final Map<String, List<UsageEvent>> bySubscription = new ConcurrentHashMap<>();

    /** 写入事件；若 eventId 已存在返回 false（调用方据此拒绝重复事件）。 */
    public boolean saveIfAbsent(UsageEvent event) {
        UsageEvent prev = byEventId.putIfAbsent(event.eventId(), event);
        if (prev != null) {
            return false;
        }
        bySubscription.computeIfAbsent(event.subscriptionId(), k -> new CopyOnWriteArrayList<>())
                .add(event);
        return true;
    }

    public boolean exists(String eventId) {
        return byEventId.containsKey(eventId);
    }

    /** 返回订阅在 [from, to) 区间内的全部事件，按发生时间排序（乱序/迟到事件均参与）。 */
    public List<UsageEvent> findBySubscriptionAndPeriod(String subscriptionId,
                                                        Instant from,
                                                        Instant to) {
        return bySubscription.getOrDefault(subscriptionId, List.of()).stream()
                .filter(e -> !e.occurredAt().isBefore(from) && e.occurredAt().isBefore(to))
                .sorted(java.util.Comparator.comparing(UsageEvent::occurredAt)
                        .thenComparing(UsageEvent::eventId))
                .toList();
    }
}
