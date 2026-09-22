package com.github.highcumontoa.subscriptionbillingjava.store;

import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SubscriptionRepository {
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public void save(Subscription subscription) {
        subscriptions.put(subscription.getId(), subscription);
    }

    public Optional<Subscription> find(String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    public List<Subscription> findAll() {
        return List.copyOf(subscriptions.values());
    }
}
