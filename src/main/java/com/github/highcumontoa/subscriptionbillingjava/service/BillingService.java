package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.BillAssembler;
import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.error.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.BillStatus;
import com.github.highcumontoa.subscriptionbillingjava.model.PaymentAttempt;
import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import com.github.highcumontoa.subscriptionbillingjava.model.RetryPolicy;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.model.UsageEvent;
import com.github.highcumontoa.subscriptionbillingjava.service.gateway.PaymentGateway;
import com.github.highcumontoa.subscriptionbillingjava.service.gateway.PaymentResult;
import com.github.highcumontoa.subscriptionbillingjava.store.BillRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.PlanRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.SubscriptionRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.UsageRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.lock.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillRepository billRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final UsageRepository usageRepository;
    private final BillAssembler billAssembler;
    private final PaymentGateway paymentGateway;
    private final LockManager lockManager;
    private final TimeService timeService;
    private final RetryPolicy retryPolicy;

    public BillingService(BillRepository billRepository,
                          SubscriptionRepository subscriptionRepository,
                          PlanRepository planRepository,
                          UsageRepository usageRepository,
                          BillAssembler billAssembler,
                          PaymentGateway paymentGateway,
                          LockManager lockManager,
                          TimeService timeService,
                          RetryPolicy retryPolicy) {
        this.billRepository = billRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.usageRepository = usageRepository;
        this.billAssembler = billAssembler;
        this.paymentGateway = paymentGateway;
        this.lockManager = lockManager;
        this.timeService = timeService;
        this.retryPolicy = retryPolicy;
    }

    /** 只读获取账单（测试/对账用）。 */
    public Bill getBill(String billId) {
        return billRepository.find(billId)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND,
                        "账单不存在: " + billId));
    }

    /**
     * 生成（或重算）某账期账单。每订阅每账期唯一一张账单：
     * DRAFT/FAILED 状态可反复重算（补报用量后口径稳定且可复现）；
     * CONFIRMED/PAID/VOID 状态拒绝重算，防止篡改。
     */
    public Bill generateBill(String subscriptionId, long periodIndex) {
        Subscription sub = subscriptionRepository.find(subscriptionId)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "订阅不存在: " + subscriptionId));
        Instant periodStart = timeService.periodStart(sub.getAnchor(), periodIndex);
        Instant periodEnd = timeService.periodEnd(sub.getAnchor(), periodIndex);

        ReentrantLock lock = lockManager.get("bill:" + subscriptionId + "@" + periodStart);
        lock.lock();
        try {
            Bill bill = billRepository.findBySubscriptionAndPeriod(subscriptionId, periodStart)
                    .orElse(null);
            if (bill != null && bill.getStatus() != BillStatus.DRAFT
                    && bill.getStatus() != BillStatus.PAYMENT_FAILED
                    && bill.getStatus() != BillStatus.DUNNING) {
                throw new BillingException(ErrorCode.PERIOD_ALREADY_CLOSED,
                        "账单已关闭，禁止重算: billId=" + bill.getId()
                                + " status=" + bill.getStatus());
            }
            if (bill == null) {
                bill = new Bill(UUID.randomUUID().toString(), subscriptionId,
                        periodStart, periodEnd);
                bill.setStatus(BillStatus.DRAFT);
            }

            Map<String, Plan> plans = loadPlans();
            List<UsageEvent> events = usageRepository
                    .findBySubscriptionAndPeriod(subscriptionId, periodStart, periodEnd);
            billAssembler.assemble(bill, sub, plans, events);
            billRepository.save(bill);
            log.info("[账单生成] billId={} sub={} 账期#{} 事件数={} 明细数={} 总额={}分 status={}",
                    bill.getId(), subscriptionId, periodIndex, events.size(),
                    bill.getLineItems().size(), bill.getTotalCents(), bill.getStatus());
            return bill;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Plan> loadPlans() {
        Map<String, Plan> plans = new HashMap<>();
        for (Plan p : planRepository.findAll()) {
            plans.put(p.code(), p);
        }
        return plans;
    }

    /**
     * 确认并收款（幂等）：
     * - 同一 idempotencyKey 重复确认直接返回既有账单，不产生新扣款；
     * - 不同 idempotencyKey 重复确认返回 BILL_ALREADY_CONFIRMED；
     * - 收款失败时置 PAYMENT_FAILED 并按重试策略安排 nextRetryAt（尚未耗尽），
     *   已耗尽则直接 DUNNING。
     */
    public Bill confirmAndCollect(String billId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "idempotencyKey 不能为空");
        }
        Bill bill = billRepository.find(billId)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND,
                        "账单不存在: " + billId));
        ReentrantLock lock = lockManager.get("bill:" + bill.getSubscriptionId()
                + "@" + bill.getPeriodStart());
        lock.lock();
        try {
            if (bill.getStatus() == BillStatus.PAID
                    || bill.getStatus() == BillStatus.CONFIRMED) {
                if (idempotencyKey.equals(bill.getConfirmedIdempotencyKey())) {
                    log.info("[账单确认-幂等命中] billId={} key={} status={} 不重复扣款",
                            billId, idempotencyKey, bill.getStatus());
                    return bill;
                }
                throw new BillingException(ErrorCode.BILL_ALREADY_CONFIRMED,
                        "账单已被其他确认请求提交: billId=" + billId
                                + " existingKey=" + bill.getConfirmedIdempotencyKey());
            }
            if (bill.getStatus() == BillStatus.VOID) {
                throw new BillingException(ErrorCode.BILL_NOT_CONFIRMABLE,
                        "已作废账单不能确认: billId=" + billId);
            }
            if (bill.getStatus() != BillStatus.DRAFT
                    && bill.getStatus() != BillStatus.PAYMENT_FAILED
                    && bill.getStatus() != BillStatus.DUNNING) {
                throw new BillingException(ErrorCode.BILL_NOT_CONFIRMABLE,
                        "当前状态不能确认: " + bill.getStatus());
            }

            bill.setConfirmedIdempotencyKey(idempotencyKey);
            bill.setConfirmedAt(timeService.now());
            return attemptCharge(bill);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 手动/催收驱动重试：必须到 nextRetryAt 之后；同一账单串行，成功幂等收尾。
     */
    public Bill retryPayment(String billId) {
        Bill bill = billRepository.find(billId)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND,
                        "账单不存在: " + billId));
        ReentrantLock lock = lockManager.get("bill:" + bill.getSubscriptionId()
                + "@" + bill.getPeriodStart());
        lock.lock();
        try {
            if (bill.getStatus() == BillStatus.PAID) {
                log.info("[收款重试-已支付幂等返回] billId={}", billId);
                return bill;
            }
            if (bill.getStatus() == BillStatus.DUNNING) {
                throw new BillingException(ErrorCode.RETRY_EXHAUSTED,
                        "重试次数已耗尽，账单在催收中: billId=" + billId);
            }
            if (bill.getStatus() != BillStatus.PAYMENT_FAILED) {
                throw new BillingException(ErrorCode.PAYMENT_NO_ACTIVE_RETRY,
                        "账单不在待重试状态: " + bill.getStatus());
            }
            Instant now = timeService.now();
            if (bill.getNextRetryAt() != null && now.isBefore(bill.getNextRetryAt())) {
                throw new BillingException(ErrorCode.PAYMENT_RETRY_TOO_EARLY,
                        "未到重试时间: nextRetryAt=" + bill.getNextRetryAt() + " now=" + now);
            }
            return attemptCharge(bill);
        } finally {
            lock.unlock();
        }
    }

    /** 在持有账单锁的前提下发起一次扣款并推进状态机。 */
    private Bill attemptCharge(Bill bill) {
        int attemptNo = bill.getAttempts().size() + 1;
        PaymentResult result = paymentGateway.charge(bill.getId(), bill.getTotalCents(), attemptNo);
        bill.getAttempts().add(new PaymentAttempt(attemptNo, timeService.now(),
                result.success(), result.failureCode(), bill.getConfirmedIdempotencyKey()));

        if (result.success()) {
            bill.setStatus(BillStatus.PAID);
            bill.setNextRetryAt(null);
            bill.setRetryCount(0);
            bill.setDunningNote(null);
            log.info("[收款成功] billId={} attempt#{} 金额={}分",
                    bill.getId(), attemptNo, bill.getTotalCents());
        } else {
            bill.setStatus(BillStatus.PAYMENT_FAILED);
            bill.setRetryCount(bill.getAttempts().size());
            if (attemptNo >= retryPolicy.maxAttempts()) {
                bill.setStatus(BillStatus.DUNNING);
                bill.setNextRetryAt(null);
                bill.setDunningNote("重试 " + retryPolicy.maxAttempts()
                        + " 次仍失败(" + result.failureCode() + ")，进入人工催收");
                log.warn("[收款耗尽-催收] billId={} attempts={} 末次失败={}",
                        bill.getId(), attemptNo, result.failureCode());
            } else {
                Duration backoff = retryPolicy.backoff().size() >= attemptNo
                        ? retryPolicy.backoff().get(attemptNo - 1)
                        : retryPolicy.backoff().get(retryPolicy.backoff().size() - 1);
                bill.setNextRetryAt(timeService.now().plus(backoff));
                log.warn("[收款失败-排程重试] billId={} attempt#{} code={} nextRetryAt={}",
                        bill.getId(), attemptNo, result.failureCode(), bill.getNextRetryAt());
            }
        }
        billRepository.save(bill);
        return bill;
    }
}
