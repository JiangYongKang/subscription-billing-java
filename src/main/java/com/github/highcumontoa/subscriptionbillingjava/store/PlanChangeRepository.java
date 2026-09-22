package com.github.highcumontoa.subscriptionbillingjava.store;

import com.github.highcumontoa.subscriptionbillingjava.model.PlanChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlanChangeRepository {
    private final Map<String, List<PlanChange>> changesBySubscription = new ConcurrentHashMap<>();

    public void append(PlanChange change) {
        changesBySubscription
                .computeIfAbsent(change.subscriptionId(), k -> new CopyOnWriteArrayList<>())
                .add(change);
    }

    public List<PlanChange> findBySubscription(String subscriptionId) {
        return List.copyOf(changesBySubscription.getOrDefault(subscriptionId, List.of()));
    }

    public long countBySubscription(String subscriptionId) {
        return changesBySubscription.getOrDefault(subscriptionId, List.of()).size();
    }
}
