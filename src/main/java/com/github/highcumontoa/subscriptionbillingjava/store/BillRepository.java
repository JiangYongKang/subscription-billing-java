package com.github.highcumontoa.subscriptionbillingjava.store;

import com.github.highcumontoa.subscriptionbillingjava.model.Bill;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BillRepository {

    private final Map<String, Bill> byId = new ConcurrentHashMap<>();
    private final Map<String, Bill> bySubscriptionPeriod = new ConcurrentHashMap<>();

    public void save(Bill bill) {
        byId.put(bill.getId(), bill);
        bySubscriptionPeriod.put(key(bill.getSubscriptionId(), bill.getPeriodStart()), bill);
    }

    public Optional<Bill> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Bill> findBySubscriptionAndPeriod(String subscriptionId, Instant periodStart) {
        return Optional.ofNullable(bySubscriptionPeriod.get(key(subscriptionId, periodStart)));
    }

    public List<Bill> findAll() {
        return List.copyOf(byId.values());
    }

    private static String key(String subscriptionId, Instant periodStart) {
        return subscriptionId + "@" + periodStart;
    }
}
