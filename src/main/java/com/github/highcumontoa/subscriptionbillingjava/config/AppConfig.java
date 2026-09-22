package com.github.highcumontoa.subscriptionbillingjava.config;

import com.github.highcumontoa.subscriptionbillingjava.model.RetryPolicy;
import com.github.highcumontoa.subscriptionbillingjava.store.BillRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.PlanChangeRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.PlanRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.SubscriptionRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.UsageRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.lock.LockManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 本地装配：内存仓储 + 分段锁 + 默认重试策略（最多 3 次尝试，退避 1 分钟、10 分钟）。
 */
@Configuration
public class AppConfig {

    @Bean
    public LockManager lockManager() {
        return new LockManager();
    }

    @Bean
    public PlanRepository planRepository() {
        return new PlanRepository();
    }

    @Bean
    public SubscriptionRepository subscriptionRepository() {
        return new SubscriptionRepository();
    }

    @Bean
    public PlanChangeRepository planChangeRepository() {
        return new PlanChangeRepository();
    }

    @Bean
    public UsageRepository usageRepository() {
        return new UsageRepository();
    }

    @Bean
    public BillRepository billRepository() {
        return new BillRepository();
    }

    @Bean
    public RetryPolicy retryPolicy() {
        return new RetryPolicy(3, List.of(Duration.ofMinutes(1), Duration.ofMinutes(10)));
    }
}
