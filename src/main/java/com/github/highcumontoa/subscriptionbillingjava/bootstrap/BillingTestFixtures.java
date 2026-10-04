package com.github.highcumontoa.subscriptionbillingjava.bootstrap;

import com.github.highcumontoa.subscriptionbillingjava.billing.LocalPaymentGateway;
import com.github.highcumontoa.subscriptionbillingjava.billing.RetryPolicy;
import com.github.highcumontoa.subscriptionbillingjava.repo.InvoiceRepository;
import com.github.highcumontoa.subscriptionbillingjava.repo.PlanRepository;
import com.github.highcumontoa.subscriptionbillingjava.repo.SubscriptionRepository;
import com.github.highcumontoa.subscriptionbillingjava.repo.UsageEventRepository;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.service.DunningService;
import com.github.highcumontoa.subscriptionbillingjava.service.PricingService;
import com.github.highcumontoa.subscriptionbillingjava.service.SubscriptionService;
import com.github.highcumontoa.subscriptionbillingjava.service.UsageService;

import java.time.Duration;
import java.time.Instant;

/**
 * 纯本地装配：无 Spring、无外部资源；全部组件为内存实现，时间由 ManualClock 控制。
 */
public final class BillingTestFixtures {

    public final ManualClock clock;
    public final PlanRepository planRepository;
    public final SubscriptionRepository subscriptionRepository;
    public final UsageEventRepository eventRepository;
    public final InvoiceRepository invoiceRepository;
    public final LocalPaymentGateway gateway;
    public final PricingService pricingService;
    public final SubscriptionService subscriptionService;
    public final UsageService usageService;
    public final BillingService billingService;
    public final DunningService dunningService;

    /** 默认重试策略：1天、3天、7天（共 4 次尝试）。 */
    public static final RetryPolicy DEFAULT_POLICY = new RetryPolicy(
            java.util.List.of(Duration.ofDays(1).toMillis(), Duration.ofDays(3).toMillis(),
                    Duration.ofDays(7).toMillis()));

    private BillingTestFixtures(long epochStart) {
        this.clock = new ManualClock(epochStart);
        this.planRepository = new PlanRepository();
        this.subscriptionRepository = new SubscriptionRepository();
        this.eventRepository = new UsageEventRepository();
        this.invoiceRepository = new InvoiceRepository();
        this.gateway = new LocalPaymentGateway();
        this.pricingService = new PricingService();
        this.subscriptionService = new SubscriptionService(
                subscriptionRepository, planRepository, clock);
        this.usageService = new UsageService(eventRepository, subscriptionRepository, clock);
        this.subscriptionService.setUsageService(usageService);
        this.billingService = new BillingService(invoiceRepository, subscriptionRepository,
                planRepository, usageService, pricingService, gateway, clock);
        this.dunningService = new DunningService(invoiceRepository, gateway, clock, billingService);
        this.dunningService.configure(DEFAULT_POLICY);
        // 迟到补报闭环：用量侧接受迟到事件后自动联动计费侧重算/补差
        this.usageService.setLateUsageHandler(billingService::processLateUsage);
    }

    public static BillingTestFixtures create() {
        // 固定一个整点基准，保证所有测试时间戳确定
        return new BillingTestFixtures(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    }

    /** 30 天账期的毫秒数。 */
    public static long days(long d) {
        return Duration.ofDays(d).toMillis();
    }
}
