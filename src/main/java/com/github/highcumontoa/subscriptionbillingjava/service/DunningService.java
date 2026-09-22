package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.billing.LocalPaymentGateway;
import com.github.highcumontoa.subscriptionbillingjava.billing.RetryPolicy;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.repo.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 收款失败催收链路。
 *
 * <p>重试语义：
 * <ul>
 *   <li>只有 DUNNING 账单可重试；未到 nextRetryAt 调用 -> RETRY_NOT_DUE（可区分的拒绝原因）；</li>
 *   <li>重试成功 -> PAID（终态幂等）；重试失败 -> 按 {@link RetryPolicy} 继续调度或 FAILED；</li>
 *   <li>FAILED 账单再调用 -> RETRY_EXHAUSTED；</li>
 *   <li>每次真实扣款在订阅级锁内只发生一次，PAID 后任何重试入口都不再调用网关。</li>
 * </ul>
 */
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private final InvoiceRepository invoiceRepository;
    private final LocalPaymentGateway gateway;
    private final ClockSource clock;
    private final BillingService billingService;

    private RetryPolicy retryPolicy = new RetryPolicy(
            List.of(Duration.ofDays(1).toMillis(), Duration.ofDays(3).toMillis(),
                    Duration.ofDays(7).toMillis()));

    public DunningService(InvoiceRepository invoiceRepository,
                          LocalPaymentGateway gateway,
                          ClockSource clock,
                          BillingService billingService) {
        this.invoiceRepository = invoiceRepository;
        this.gateway = gateway;
        this.clock = clock;
        this.billingService = billingService;
    }

    public void configure(RetryPolicy policy) {
        if (policy == null || policy.intervalsMillis().isEmpty()) {
            throw new IllegalArgumentException("RetryPolicy 必须至少包含一个重试间隔");
        }
        this.retryPolicy = policy;
        billingService.setRetryPolicy(policy);
        log.info("[dunning] retry policy configured: {} attempts, intervals={}ms",
                policy.maxAttempts(), policy.intervalsMillis());
    }

    public Invoice processRetry(String invoiceId) {
        Invoice invoice = invoiceRepository.requireById(invoiceId);
        return invoiceRepository.withLock(invoice.getSubscriptionId(), () -> {
            long now = clock.nowMillis();
            if (invoice.getStatus() == InvoiceStatus.PAID) {
                log.info("[dunning] idempotent PAID id={} decision=NOOP (no gateway call)",
                        invoice.getId());
                return invoice;
            }
            if (invoice.getStatus() == InvoiceStatus.FAILED) {
                throw reject(ErrorCode.RETRY_EXHAUSTED,
                        "重试次数已耗尽，账单终态 FAILED: id=" + invoice.getId());
            }
            if (invoice.getStatus() != InvoiceStatus.DUNNING) {
                throw reject(ErrorCode.RETRY_NOT_DUE,
                        "账单未进入催收流程，不能重试: id=" + invoice.getId()
                                + " status=" + invoice.getStatus());
            }
            if (now < invoice.getNextRetryAt()) {
                throw reject(ErrorCode.RETRY_NOT_DUE,
                        "尚未到重试时间: id=" + invoice.getId()
                                + " now=" + now + " nextRetryAt=" + invoice.getNextRetryAt()
                                + " remaining=" + (invoice.getNextRetryAt() - now) + "ms");
            }

            int attempt = invoice.getAttempt() + 1;
            invoice.setAttempt(attempt);
            var result = gateway.charge(invoice, attempt);
            invoice.getAttemptAt().add(now);
            invoice.getAttemptResults().add(
                    (result.success ? "SUCCESS" : "DECLINED") + ":" + result.reason
                            + (result.reference != null ? ":" + result.reference : ""));
            if (result.success) {
                invoice.setStatus(InvoiceStatus.PAID);
                log.info("[dunning] retry decision=PAID id={} attempt={}/{} ref={}",
                        invoice.getId(), attempt, retryPolicy.maxAttempts(), result.reference);
            } else {
                billingService.applyFailure(invoice, attempt, now, result.reason);
            }
            invoiceRepository.save(invoice);
            return invoice;
        });
    }

    private BillingException reject(ErrorCode code, String message) {
        log.warn("[dunning] rejected code={} detail={}", code, message);
        return new BillingException(code, message);
    }
}
