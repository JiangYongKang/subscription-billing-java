package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.BillStatus;
import com.github.highcumontoa.subscriptionbillingjava.store.BillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 催收：扫描 PAYMENT_FAILED 且到达 nextRetryAt 的账单，调用 BillingService 重试；
 * 重试逻辑与手动重试完全相同（同一把账单锁、同一幂等约束），重试次数耗尽由 BillingService
 * 置为 DUNNING。
 */
@Service
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final BillRepository billRepository;
    private final BillingService billingService;
    private final TimeService timeService;

    public DunningService(BillRepository billRepository, BillingService billingService,
                          TimeService timeService) {
        this.billRepository = billRepository;
        this.billingService = billingService;
        this.timeService = timeService;
    }

    public List<Bill> runRetryCycle() {
        Instant now = timeService.now();
        List<Bill> due = billRepository.findAll().stream()
                .filter(b -> b.getStatus() == BillStatus.PAYMENT_FAILED)
                .filter(b -> b.getNextRetryAt() != null && !now.isBefore(b.getNextRetryAt()))
                .toList();
        List<Bill> results = new ArrayList<>();
        for (Bill b : due) {
            log.info("[催收扫描] 触发重试 billId={} attempts={} nextRetryAt={}",
                    b.getId(), b.getAttempts().size(), b.getNextRetryAt());
            results.add(billingService.retryPayment(b.getId()));
        }
        return results;
    }
}
