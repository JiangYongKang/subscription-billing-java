package com.github.highcumontoa.subscriptionbillingjava.store;

import com.github.highcumontoa.subscriptionbillingjava.model.Plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PlanRepository {
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    public void save(Plan plan) {
        plans.put(plan.code(), plan);
    }

    public Optional<Plan> find(String code) {
        return Optional.ofNullable(plans.get(code));
    }

    public List<Plan> findAll() {
        return List.copyOf(plans.values());
    }
}
