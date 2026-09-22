package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.billing.Money;
import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.error.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.model.ChangeType;
import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import com.github.highcumontoa.subscriptionbillingjava.model.PlanChange;
import com.github.highcumontoa.subscriptionbillingjava.model.Segment;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.model.SubscriptionStatus;
import com.github.highcumontoa.subscriptionbillingjava.store.PlanChangeRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.PlanRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.SubscriptionRepository;
import com.github.highcumontoa.subscriptionbillingjava.store.lock.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** 不允许跨档直降（配额虽满足，但商务规则要求逐级降）。 */
    private static final java.util.Set<String> FORBIDDEN_DIRECT_DOWNGRADE =
            java.util.Set.of("ENTERPRISE->BASIC", "ENTERPRISE->SMART");

    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanChangeRepository planChangeRepository;
    private final LockManager lockManager;
    private final TimeService timeService;

    public SubscriptionService(PlanRepository planRepository,
                               SubscriptionRepository subscriptionRepository,
                               PlanChangeRepository planChangeRepository,
                               LockManager lockManager,
                               TimeService timeService) {
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planChangeRepository = planChangeRepository;
        this.lockManager = lockManager;
        this.timeService = timeService;
    }

    public Subscription create(String planCode) {
        Plan plan = planRepository.find(planCode)
                .orElseThrow(() -> new BillingException(ErrorCode.PLAN_NOT_FOUND,
                        "套餐不存在: " + planCode));
        Instant now = timeService.now();
        Subscription sub = new Subscription(UUID.randomUUID().toString(), planCode, now, now);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(timeService.plusMonths(now, 1));
        sub.getSegments().add(new Segment(planCode, now, sub.getCurrentPeriodEnd(), null));
        sub.setVersion(0);
        subscriptionRepository.save(sub);
        log.info("[订阅创建] id={} plan={} anchor={} 首账期=[{},{})",
                sub.getId(), planCode, now, now, sub.getCurrentPeriodEnd());
        return sub;
    }

    /**
     * 套餐变更（立即生效）。expectedVersion 非空时做乐观并发校验：
     * 同一订阅的并发变更只有一个能提交，另一个收到 CONCURRENT_CHANGE_CONFLICT，
     * 绝不会重复计费或丢失更新。
     */
    public PlanChange changePlan(String subscriptionId, String targetPlanCode,
                                 Instant effectiveAt, Long expectedVersion) {
        ReentrantLock lock = lockManager.get("sub:" + subscriptionId);
        lock.lock();
        try {
            Subscription sub = subscriptionRepository.find(subscriptionId)
                    .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                            "订阅不存在: " + subscriptionId));
            if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_CANCELLED,
                        "订阅已取消，禁止变更: " + subscriptionId);
            }
            if (sub.getStatus() == SubscriptionStatus.PENDING_CANCEL) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_PENDING_CANCEL,
                        "订阅已预约取消，禁止变更: " + subscriptionId);
            }
            if (expectedVersion != null && expectedVersion != sub.getVersion()) {
                throw new BillingException(ErrorCode.CONCURRENT_CHANGE_CONFLICT,
                        "并发冲突: expectedVersion=" + expectedVersion
                                + " actualVersion=" + sub.getVersion());
            }

            String fromPlanCode = sub.getPlanCode();
            if (fromPlanCode.equals(targetPlanCode)) {
                throw new BillingException(ErrorCode.SAME_PLAN_CHANGE,
                        "目标套餐与当前套餐相同: " + targetPlanCode);
            }
            Plan from = planRepository.find(fromPlanCode)
                    .orElseThrow(() -> new BillingException(ErrorCode.PLAN_NOT_FOUND,
                            "当前套餐不存在: " + fromPlanCode));
            Plan to = planRepository.find(targetPlanCode)
                    .orElseThrow(() -> new BillingException(ErrorCode.PLAN_NOT_FOUND,
                            "目标套餐不存在: " + targetPlanCode));

            Instant now = timeService.now();
            Instant eff = effectiveAt == null ? now : effectiveAt;
            Instant periodStart = sub.getCurrentPeriodStart();
            Instant periodEnd = sub.getCurrentPeriodEnd();
            if (eff.isBefore(periodStart) || !eff.isBefore(periodEnd)) {
                throw new BillingException(ErrorCode.INVALID_EFFECTIVE_TIME,
                        "生效时间不在当前账期内: eff=" + eff + " 账期=[" + periodStart + "," + periodEnd + ")");
            }
            if (eff.isBefore(now)) {
                throw new BillingException(ErrorCode.INVALID_EFFECTIVE_TIME,
                        "生效时间不能早于当前时间: eff=" + eff + " now=" + now);
            }

            ChangeType type = to.amountCents() >= from.amountCents()
                    ? ChangeType.UPGRADE : ChangeType.DOWNGRADE;
            if (type == ChangeType.DOWNGRADE) {
                validateDowngrade(sub, from, to, periodStart, periodEnd);
            }

            // 闭合旧片段、开新片段（append-only），新片段持续到账期结束；
            // 差额仅按“基础费”比例预估，账期最终账单为唯一结算依据，避免重复计费。
            List<Segment> segments = sub.getSegments();
            Segment last = segments.get(segments.size() - 1);
            long periodSeconds = java.time.Duration.between(periodStart, periodEnd).getSeconds();
            long elapsedSeconds = java.time.Duration.between(periodStart, eff).getSeconds();
            long remainingSeconds = java.time.Duration.between(eff, periodEnd).getSeconds();
            long oldRemaining = Money.prorate(from.amountCents(), remainingSeconds, periodSeconds);
            long newRemaining = Money.prorate(to.amountCents(), remainingSeconds, periodSeconds);
            long delta = newRemaining - oldRemaining;

            segments.set(segments.size() - 1,
                    new Segment(last.planCode(), last.start(), eff, last.reason()));
            segments.add(new Segment(targetPlanCode, eff, periodEnd, type));

            long baseVersion = sub.getVersion();
            sub.setPlanCode(targetPlanCode);
            sub.setVersion(baseVersion + 1);
            subscriptionRepository.save(sub);

            PlanChange change = new PlanChange(UUID.randomUUID().toString(), subscriptionId,
                    type, fromPlanCode, targetPlanCode, eff, delta, baseVersion,
                    "账期内变更，剩余时段比例分摊差额(仅基础费)=" + delta + "分");
            planChangeRepository.append(change);
            log.info("[套餐变更] sub={} {} -> {} type={} eff={} 已用={}s/账期={}s 比例差额={}分 version={}->{}",
                    subscriptionId, fromPlanCode, targetPlanCode, type, eff,
                    elapsedSeconds, periodSeconds, delta, baseVersion, sub.getVersion());
            return change;
        } finally {
            lock.unlock();
        }
    }

    private void validateDowngrade(Subscription sub, Plan from, Plan to,
                                   Instant periodStart, Instant periodEnd) {
        if (FORBIDDEN_DIRECT_DOWNGRADE.contains(from.code() + "->" + to.code())) {
            throw new BillingException(ErrorCode.DOWNGRADE_NOT_ALLOWED_TIER,
                    "不允许从 " + from.code() + " 直接降级到 " + to.code());
        }
        if (to.quotaIncluded() < from.quotaIncluded()) {
            throw new BillingException(ErrorCode.DOWNGRADE_QUOTA_LIMIT,
                    "目标套餐配额(" + to.quotaIncluded() + ")低于当前套餐配额("
                            + from.quotaIncluded() + ")，当前账期不允许降级");
        }
    }

    /**
     * 续订：仅在当前账期已到期时允许（测试通过推进时钟复现）。
     * 关闭当前账期最后一个片段，开新账期与新片段，并产生 RENEW 流水。
     */
    public PlanChange renew(String subscriptionId) {
        ReentrantLock lock = lockManager.get("sub:" + subscriptionId);
        lock.lock();
        try {
            Subscription sub = subscriptionRepository.find(subscriptionId)
                    .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                            "订阅不存在: " + subscriptionId));
            if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_CANCELLED,
                        "非 ACTIVE 状态不可续订: " + sub.getStatus());
            }
            Instant now = timeService.now();
            if (now.isBefore(sub.getCurrentPeriodEnd())) {
                throw new BillingException(ErrorCode.INVALID_EFFECTIVE_TIME,
                        "当前账期尚未到期，不能续订: end=" + sub.getCurrentPeriodEnd());
            }
            Instant newStart = sub.getCurrentPeriodEnd();
            Instant newEnd = timeService.plusMonths(newStart, 1);
            long baseVersion = sub.getVersion();
            sub.getSegments().add(new Segment(sub.getPlanCode(), newStart, newEnd, ChangeType.RENEW));
            sub.setCurrentPeriodStart(newStart);
            sub.setCurrentPeriodEnd(newEnd);
            sub.setVersion(baseVersion + 1);
            subscriptionRepository.save(sub);

            PlanChange change = new PlanChange(UUID.randomUUID().toString(), subscriptionId,
                    ChangeType.RENEW, sub.getPlanCode(), sub.getPlanCode(), newStart, 0,
                    baseVersion, "续订新账期 [" + newStart + "," + newEnd + ")");
            planChangeRepository.append(change);
            log.info("[续订] sub={} 新账期=[{},{}) version={}->{}",
                    subscriptionId, newStart, newEnd, baseVersion, sub.getVersion());
            return change;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取消：effectiveAt 为空或 <= now 表示立即取消（CANCELLED，当前账期仍按片段出账）；
     * 未来时间表示预约取消（PENDING_CANCEL，到期后由结算关闭）。
     */
    public Subscription cancel(String subscriptionId, Instant effectiveAt) {
        ReentrantLock lock = lockManager.get("sub:" + subscriptionId);
        lock.lock();
        try {
            Subscription sub = subscriptionRepository.find(subscriptionId)
                    .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                            "订阅不存在: " + subscriptionId));
            if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_CANCELLED,
                        "订阅已取消，不能重复取消");
            }
            Instant now = timeService.now();
            Instant eff = effectiveAt == null ? now : effectiveAt;
            if (eff.isBefore(now)) {
                throw new BillingException(ErrorCode.INVALID_EFFECTIVE_TIME,
                        "取消时间不能早于当前时间: eff=" + eff);
            }
            long baseVersion = sub.getVersion();
            if (!eff.isAfter(now)) {
                sub.setStatus(SubscriptionStatus.CANCELLED);
                List<Segment> segments = sub.getSegments();
                Segment last = segments.get(segments.size() - 1);
                if (last.end().isAfter(now)) {
                    segments.set(segments.size() - 1,
                            new Segment(last.planCode(), last.start(), now, last.reason()));
                }
            } else {
                sub.setStatus(SubscriptionStatus.PENDING_CANCEL);
            }
            sub.setVersion(baseVersion + 1);
            subscriptionRepository.save(sub);
            log.info("[取消] sub={} status={} eff={} version={}->{}",
                    subscriptionId, sub.getStatus(), eff, baseVersion, sub.getVersion());
            return sub;
        } finally {
            lock.unlock();
        }
    }
}
