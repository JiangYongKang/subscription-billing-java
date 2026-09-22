package com.github.highcumontoa.subscriptionbillingjava.config;

import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import com.github.highcumontoa.subscriptionbillingjava.model.PricingTier;
import com.github.highcumontoa.subscriptionbillingjava.store.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 本地种子套餐（金额单位：分）：
 * BASIC       1000/月，含量 1,000，超额阶梯 3/2/1 分
 * PRO         5000/月，含量 5,000，超额阶梯 2/1 分
 * SMART       8000/月，含量 20,000（高价小配额差档，用于校验降级档位约束）
 * ENTERPRISE 15000/月，含量 20,000，超额 1 分
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public ApplicationRunner seedPlans(PlanRepository planRepository) {
        return args -> {
            planRepository.save(new Plan("BASIC", "基础版", 1000, 1_000, List.of(
                    new PricingTier(1_000, 3),
                    new PricingTier(5_000, 2),
                    new PricingTier(Long.MAX_VALUE, 1))));
            planRepository.save(new Plan("PRO", "专业版", 5_000, 5_000, List.of(
                    new PricingTier(5_000, 2),
                    new PricingTier(Long.MAX_VALUE, 1))));
            planRepository.save(new Plan("SMART", "轻奢版", 8_000, 20_000, List.of(
                    new PricingTier(Long.MAX_VALUE, 2))));
            planRepository.save(new Plan("ENTERPRISE", "企业版", 15_000, 20_000, List.of(
                    new PricingTier(Long.MAX_VALUE, 1))));
            log.info("种子套餐已写入: BASIC / PRO / SMART / ENTERPRISE");
        };
    }
}
