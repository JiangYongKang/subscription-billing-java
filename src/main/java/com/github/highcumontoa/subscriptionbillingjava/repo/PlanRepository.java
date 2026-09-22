package com.github.highcumontoa.subscriptionbillingjava.repo;

import com.github.highcumontoa.subscriptionbillingjava.domain.OverageTier;
import com.github.highcumontoa.subscriptionbillingjava.domain.Plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 本地套餐目录（内存模拟），预置 BASIC / PRO / ENTERPRISE 三档。 */
public class PlanRepository {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    public PlanRepository() {
        save(new Plan("BASIC", "基础版",
                1000L, 100L,
                List.of(new OverageTier(0L, 100L, 5L),
                        new OverageTier(100L, null, 3L))));
        save(new Plan("PRO", "专业版",
                3000L, 500L,
                List.of(new OverageTier(0L, 500L, 4L),
                        new OverageTier(500L, null, 2L))));
        save(new Plan("ENTERPRISE", "企业版",
                8000L, 2000L,
                List.of(new OverageTier(0L, null, 2L))));
    }

    public Plan findByCode(String code) {
        return plans.get(code);
    }

    public Plan requireByCode(String code) {
        Plan plan = plans.get(code);
        if (plan == null) {
            throw new com.github.highcumontoa.subscriptionbillingjava.exception.BillingException(
                    com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode.PLAN_NOT_FOUND,
                    "套餐不存在: " + code);
        }
        return plan;
    }

    public void save(Plan plan) {
        plans.put(plan.code(), plan);
    }

    public List<Plan> findAll() {
        return List.copyOf(plans.values());
    }
}
