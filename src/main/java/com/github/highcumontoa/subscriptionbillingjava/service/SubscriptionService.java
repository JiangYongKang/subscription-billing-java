package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.domain.Plan;
import com.github.highcumontoa.subscriptionbillingjava.domain.PlanChange;
import com.github.highcumontoa.subscriptionbillingjava.domain.PlanChangeType;
import com.github.highcumontoa.subscriptionbillingjava.domain.PlanSegment;
import com.github.highcumontoa.subscriptionbillingjava.domain.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.domain.SubscriptionStatus;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.repo.PlanRepository;
import com.github.highcumontoa.subscriptionbillingjava.repo.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 订阅生命周期与套餐变更服务。
 *
 * <p>一致性设计：
 * <ul>
 *   <li>所有对同一订阅的写操作都在订阅级互斥锁内完成（无重复计费窗口）；</li>
 *   <li>changePlan/cancel 携带 expectedVersion 做乐观检查，版本过期即拒绝
 *       （CONCURRENT_MODIFICATION），防止丢失更新；</li>
 *   <li>周期内变更通过“关闭开口段 + 开新段”表达，段不重叠、无缝隙，
 *       比例费用由 PricingService 按段长/账期长可复现计算；</li>
 *   <li>允许账期内“补报式”变更（effectiveAt 早于当前时间）：以生效时间为界
 *       截断其之后的分段，保证最终分段序列只反映每个生效点的最新意图。</li>
 * </ul>
 */
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final ClockSource clock;
    private UsageService usageService; // 可选依赖：降级配额校验，setter 注入避免循环依赖

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               PlanRepository planRepository,
                               ClockSource clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.clock = clock;
    }

    public void setUsageService(UsageService usageService) {
        this.usageService = usageService;
    }

    /** 账期固定为 30 天，自创建时刻起对齐（本地确定性规则，无时区/历法漂移）。 */
    public static long nextPeriodEnd(long periodStart) {
        return Instant.ofEpochMilli(periodStart).plus(30, ChronoUnit.DAYS).toEpochMilli();
    }

    public Subscription create(String planCode, Long effectiveAt) {
        Plan plan = planRepository.requireByCode(planCode);
        long start = effectiveAt != null ? effectiveAt : clock.nowMillis();
        String id = "SUB-" + UUID.randomUUID();
        Subscription s = new Subscription();
        s.setId(id);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCreatedAt(start);
        s.setPeriodStart(start);
        s.setPeriodEnd(nextPeriodEnd(start));
        s.setCancelledAt(0L);
        s.setVersion(0L);
        s.getSegments().add(new PlanSegment(plan.code(), start, null));
        subscriptionRepository.save(s);
        subscriptionRepository.appendChange(new PlanChange(
                "CHG-" + UUID.randomUUID(), id, PlanChangeType.CREATE,
                null, plan.code(), start, 0L));
        log.info("[subscription] create input={{planCode={}, effectiveAt={}}} state={{id={}, "
                        + "period=[{},{}), openSegment={}}} decision=CREATED",
                planCode, start, id, s.getPeriodStart(), s.getPeriodEnd(), plan.code());
        return s;
    }

    public Subscription changePlan(String subscriptionId,
                                   String targetPlanCode,
                                   long effectiveAt,
                                   long expectedVersion) {
        return subscriptionRepository.withLock(subscriptionId, () -> {
            Subscription s = subscriptionRepository.requireById(subscriptionId);
            Plan target = planRepository.requireByCode(targetPlanCode);

            if (s.getStatus() != SubscriptionStatus.ACTIVE) {
                throw reject(ErrorCode.ILLEGAL_STATE_TRANSITION,
                        "已取消订阅不允许变更套餐: id=" + subscriptionId
                                + " status=" + s.getStatus());
            }
            if (s.getVersion() != expectedVersion) {
                throw reject(ErrorCode.CONCURRENT_MODIFICATION,
                        "并发变更被拒绝: id=" + subscriptionId
                                + " expectedVersion=" + expectedVersion
                                + " actualVersion=" + s.getVersion());
            }
            if (effectiveAt < s.getPeriodStart() || effectiveAt >= s.getPeriodEnd()) {
                throw reject(ErrorCode.EFFECTIVE_TIME_INVALID,
                        "生效时间必须落在当前账期内: effectiveAt=" + effectiveAt
                                + " period=[" + s.getPeriodStart() + "," + s.getPeriodEnd() + ")");
            }
            // “当前套餐”必须按生效时刻实际覆盖的分段来判断（补报式变更时开口段可能在生效点之后）
            String currentCode = segmentCovering(s.getSegments(), effectiveAt).planCode();
            if (currentCode.equals(target.code())) {
                throw reject(ErrorCode.PLAN_UNCHANGED,
                        "目标套餐与当前开口段套餐相同，变更无意义: " + currentCode);
            }
            // 周期内降级：生效时点之前的已用量不得超过新套餐按比例包含的额度
            Plan current = planRepository.requireByCode(currentCode);
            if (target.monthlyPriceCents() < current.monthlyPriceCents() && usageService != null) {
                long used = usageService.aggregate(subscriptionId, s.getPeriodStart(), effectiveAt);
                long allowed = new PricingService().proratedIncludedUnits(
                        target.includedUnits(), effectiveAt, s.getPeriodEnd(),
                        s.getPeriodStart(), s.getPeriodEnd());
                if (used > allowed) {
                    throw reject(ErrorCode.DOWNGRADE_QUOTA_EXCEEDED,
                            "降级被拒绝: 生效点前已用量=" + used
                                    + " 超过新套餐分段包含额度=" + allowed
                                    + " (plan=" + target.code() + ", effectiveAt=" + effectiveAt + ")");
                }
                log.info("[subscription] downgrade quota check id={} used={} allowed={} -> PASS",
                        subscriptionId, used, allowed);
            }

            // 以生效时间为界，截断其后的全部分段（支持账期内补报式变更）
            List<PlanSegment> segs = s.getSegments();
            segs.removeIf(seg -> seg.start() >= effectiveAt);
            int lastIdx = segs.size() - 1;
            PlanSegment open = segs.get(lastIdx);
            segs.set(lastIdx, new PlanSegment(open.planCode(), open.start(), effectiveAt));
            segs.add(new PlanSegment(target.code(), effectiveAt, null));

            PlanChangeType type = target.monthlyPriceCents() >= current.monthlyPriceCents()
                    ? PlanChangeType.UPGRADE : PlanChangeType.DOWNGRADE;
            long newVersion = s.getVersion() + 1;
            s.setVersion(newVersion);
            subscriptionRepository.save(s);
            subscriptionRepository.appendChange(new PlanChange(
                    "CHG-" + UUID.randomUUID(), subscriptionId, type,
                    currentCode, target.code(), effectiveAt, newVersion));
            log.info("[subscription] changePlan input={{id={}, {} -> {}, effectiveAt={}, v={}}} "
                            + "decision={} segments={}",
                    subscriptionId, currentCode, target.code(), effectiveAt, expectedVersion,
                    type, segs);
            return s;
        });
    }

    public Subscription cancel(String subscriptionId, long effectiveAt, long expectedVersion) {
        return subscriptionRepository.withLock(subscriptionId, () -> {
            Subscription s = subscriptionRepository.requireById(subscriptionId);
            if (s.getStatus() != SubscriptionStatus.ACTIVE) {
                throw reject(ErrorCode.ILLEGAL_STATE_TRANSITION,
                        "仅 ACTIVE 订阅可取消: id=" + subscriptionId
                                + " status=" + s.getStatus());
            }
            if (s.getVersion() != expectedVersion) {
                throw reject(ErrorCode.CONCURRENT_MODIFICATION,
                        "并发取消被拒绝: expectedVersion=" + expectedVersion
                                + " actualVersion=" + s.getVersion());
            }
            if (effectiveAt < s.getPeriodStart() || effectiveAt >= s.getPeriodEnd()) {
                throw reject(ErrorCode.EFFECTIVE_TIME_INVALID,
                        "取消生效时间必须落在当前账期内: effectiveAt=" + effectiveAt
                                + " period=[" + s.getPeriodStart() + "," + s.getPeriodEnd() + ")");
            }
            List<PlanSegment> segs = s.getSegments();
            segs.removeIf(seg -> seg.start() >= effectiveAt);
            int lastIdx = segs.size() - 1;
            PlanSegment open = segs.get(lastIdx);
            String fromCode = open.planCode();
            segs.set(lastIdx, new PlanSegment(open.planCode(), open.start(), effectiveAt));

            s.setStatus(SubscriptionStatus.CANCELLED);
            s.setCancelledAt(effectiveAt);
            long newVersion = s.getVersion() + 1;
            s.setVersion(newVersion);
            subscriptionRepository.save(s);
            subscriptionRepository.appendChange(new PlanChange(
                    "CHG-" + UUID.randomUUID(), subscriptionId, PlanChangeType.CANCEL,
                    fromCode, null, effectiveAt, newVersion));
            log.info("[subscription] cancel input={{id={}, effectiveAt={}, v={}}} decision=CANCELLED "
                            + "finalSegmentEnd={}",
                    subscriptionId, effectiveAt, expectedVersion, effectiveAt);
            return s;
        });
    }

    /**
     * 账期滚动：atTime &ge; 当前账期结束时推进到下一账期。
     * 跨越账期边界的分段在边界处切分，保证每个账期各自闭合、比例可独立核对。
     * 取消态订阅不续订（开口保持关闭）。
     */
    public Subscription renew(String subscriptionId, long atTime) {
        return subscriptionRepository.withLock(subscriptionId, () -> {
            Subscription s = subscriptionRepository.requireById(subscriptionId);
            if (atTime < s.getPeriodEnd()) {
                log.info("[subscription] renew id={} atTime={} < periodEnd={} decision=NOOP",
                        subscriptionId, atTime, s.getPeriodEnd());
                return s;
            }
            long oldEnd = s.getPeriodEnd();
            long newStart = oldEnd;
            long newEnd = nextPeriodEnd(newStart);

            List<PlanSegment> segs = s.getSegments();
            // 1) 跨过旧账期边界的开口段在边界处闭合
            for (int i = 0; i < segs.size(); i++) {
                PlanSegment seg = segs.get(i);
                if (seg.end() == null && seg.start() < oldEnd) {
                    segs.set(i, new PlanSegment(seg.planCode(), seg.start(), newStart));
                }
            }
            // 2) ACTIVE 订阅以旧开口段套餐开新账期的开口段；CANCELLED 不产生新段
            if (s.getStatus() == SubscriptionStatus.ACTIVE) {
                String openCode = segs.get(segs.size() - 1).planCode();
                segs.add(new PlanSegment(openCode, newStart, null));
            }
            s.setPeriodStart(newStart);
            s.setPeriodEnd(newEnd);
            long newVersion = s.getVersion() + 1;
            s.setVersion(newVersion);
            subscriptionRepository.save(s);
            subscriptionRepository.appendChange(new PlanChange(
                    "CHG-" + UUID.randomUUID(), subscriptionId, PlanChangeType.RENEW,
                    null, null, newStart, newVersion));
            log.info("[subscription] renew id={} atTime={} decision=ROLLED period=[{},{}) segments={}",
                    subscriptionId, atTime, newStart, newEnd, segs);
            return s;
        });
    }

    public List<PlanChange> history(String subscriptionId) {
        subscriptionRepository.requireById(subscriptionId);
        return subscriptionRepository.changesOf(subscriptionId);
    }

    /** 返回覆盖时间点 t 的分段：seg.start &le; t，且 seg.end 为 null 或 &gt; t。 */
    private PlanSegment segmentCovering(List<PlanSegment> segments, long t) {
        PlanSegment found = null;
        for (PlanSegment seg : segments) {
            if (seg.start() <= t && (seg.end() == null || seg.end() > t)) {
                found = seg;
            }
        }
        if (found == null) {
            throw reject(ErrorCode.EFFECTIVE_TIME_INVALID,
                    "生效时间不被任何套餐分段覆盖: effectiveAt=" + t + " segments=" + segments);
        }
        return found;
    }

    private BillingException reject(ErrorCode code, String message) {
        log.warn("[subscription] rejected code={} detail={}", code, message);
        return new BillingException(code, message);
    }
}
