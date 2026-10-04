package com.github.highcumontoa.subscriptionbillingjava.service;

import com.github.highcumontoa.subscriptionbillingjava.domain.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.domain.SubscriptionStatus;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.repo.SubscriptionRepository;
import com.github.highcumontoa.subscriptionbillingjava.repo.UsageEventRepository;
import com.github.highcumontoa.subscriptionbillingjava.usage.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 用量上报与聚合服务。
 *
 * <p>语义约定：
 * <ul>
 *   <li><b>幂等去重</b>：eventId 为全局幂等键；重复上报以 DUPLICATE_EVENT 明确拒绝
 *       （不静默丢弃、不重复计入），返回信息含已存事件便于核对；</li>
 *   <li><b>乱序与迟到</b>：事件按 occurredAt（实际发生时间）归集，与上报顺序无关；
 *       允许 occurredAt 早于上报时间的迟到事件，只要不早于订阅创建时间且不晚于
 *       当前时间 + 容忍窗口（60 天）；补报后重新聚合只依赖已存事件集合，口径稳定；</li>
 *   <li><b>聚合</b>：窗口 [from,to) 内事件按 (occurredAt,eventId) 排序求和，
 *       同批事件无论查询多少次结果一致。</li>
 * </ul>
 */
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    /** 允许的乱序/迟到容忍窗口：occurredAt 不得早于订阅创建、不得晚于当前+60天。 */
    public static final long OUT_OF_ORDER_TOLERANCE_MILLIS =
            java.time.Duration.ofDays(60).toMillis();

    /**
     * 迟到补报回调：事件 occurredAt 落在当前账期之前（历史账期）时触发，
     * 由计费侧决定重算未结清账单或生成补差调整单。setter 注入避免循环依赖。
     */
    public interface LateUsageHandler {
        void onLateUsage(String subscriptionId, long periodStart, UsageEvent event);
    }

    private final UsageEventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ClockSource clock;
    private LateUsageHandler lateUsageHandler;

    public UsageService(UsageEventRepository eventRepository,
                        SubscriptionRepository subscriptionRepository,
                        ClockSource clock) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.clock = clock;
    }

    public void setLateUsageHandler(LateUsageHandler lateUsageHandler) {
        this.lateUsageHandler = lateUsageHandler;
    }

    public UsageEvent report(String eventId,
                             String subscriptionId,
                             long quantity,
                             long occurredAt) {
        long now = clock.nowMillis();
        log.info("[usage] report input={{eventId={}, sub={}, qty={}, occurredAt={}, now={}}}",
                eventId, subscriptionId, quantity, occurredAt, now);

        if (eventId == null || eventId.isBlank()) {
            throw reject(ErrorCode.QUANTITY_INVALID, "eventId 不能为空");
        }
        if (quantity <= 0) {
            throw reject(ErrorCode.QUANTITY_INVALID,
                    "用量必须为正整数: eventId=" + eventId + " quantity=" + quantity);
        }
        Subscription s = subscriptionRepository.requireById(subscriptionId);
        if (occurredAt < s.getCreatedAt()) {
            throw reject(ErrorCode.EVENT_TOO_OLD,
                    "事件发生时间早于订阅创建时间，拒绝以免静默丢弃: eventId=" + eventId
                            + " occurredAt=" + occurredAt + " createdAt=" + s.getCreatedAt());
        }
        if (occurredAt > now + OUT_OF_ORDER_TOLERANCE_MILLIS) {
            throw reject(ErrorCode.EVENT_OUT_OF_ORDER_WINDOW,
                    "事件发生时间超出乱序容忍窗口(未来60天): eventId=" + eventId
                            + " occurredAt=" + occurredAt + " now=" + now);
        }
        UsageEvent event = new UsageEvent(eventId, subscriptionId, quantity, occurredAt, now);
        // 原子去重：重复 eventId 在仓储层抛 DUPLICATE_EVENT
        eventRepository.save(event);
        log.info("[usage] report decision=ACCEPTED eventId={} state={{sub={}, totalStored={}}}",
                eventId, subscriptionId,
                eventRepository.findBySubscriptionAndOccurredBetween(
                        subscriptionId, s.getCreatedAt(), Long.MAX_VALUE).size());
        // 迟到补报闭环：事件所属账期在上报时刻已结束 -> 定位该账期并通知计费侧
        // （不依赖订阅是否已续订：只要账期 [pStart, pEnd) 满足 pEnd <= now 即为迟到补报）
        if (lateUsageHandler != null) {
            long periodLen = SubscriptionService.nextPeriodEnd(s.getCreatedAt()) - s.getCreatedAt();
            long periodIndex = (occurredAt - s.getCreatedAt()) / periodLen;
            long periodStart = s.getCreatedAt() + periodIndex * periodLen;
            if (periodStart + periodLen <= now) {
                log.info("[usage] late report detected eventId={} occurredAt={} -> period=[{},{})",
                        eventId, occurredAt, periodStart, periodStart + periodLen);
                lateUsageHandler.onLateUsage(subscriptionId, periodStart, event);
            }
        }
        return event;
    }

    public long aggregate(String subscriptionId, long fromInclusive, long toExclusive) {
        List<UsageEvent> events = aggregatedEvents(subscriptionId, fromInclusive, toExclusive);
        long sum = events.stream().mapToLong(UsageEvent::quantity).sum();
        log.info("[usage] aggregate sub={} window=[{},{}) events={} total={}",
                subscriptionId, fromInclusive, toExclusive, events.size(), sum);
        return sum;
    }

    public List<UsageEvent> aggregatedEvents(String subscriptionId,
                                             long fromInclusive,
                                             long toExclusive) {
        List<UsageEvent> events = eventRepository
                .findBySubscriptionAndOccurredBetween(subscriptionId, fromInclusive, toExclusive);
        log.info("[usage] aggregation basis sub={} window=[{},{}) basis={}",
                subscriptionId, fromInclusive, toExclusive, events);
        return events;
    }

    private BillingException reject(ErrorCode code, String message) {
        log.warn("[usage] rejected code={} detail={}", code, message);
        return new BillingException(code, message);
    }
}
