package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.TimeService;
import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.error.ErrorCode;
import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.service.BillingService;
import com.github.highcumontoa.subscriptionbillingjava.service.SubscriptionService;
import com.github.highcumontoa.subscriptionbillingjava.service.UsageService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用量上报：重复事件显式拒绝（不重复计入）；乱序/迟到事件重算结果稳定；
 * 账单确认后的补报被拒绝（USAGE_LATE_PERIOD_CLOSED，不静默丢弃）。
 */
@SpringBootTest
class UsageReportingTest {

    private static final Logger log = LoggerFactory.getLogger(UsageReportingTest.class);

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UsageService usageService;
    @Autowired private BillingService billingService;
    @Autowired private TimeService timeService;

    @Test
    void duplicate_outOfOrder_lateEvents_areHandledDistinctly() {
        Instant t0 = Instant.parse("2026-05-01T00:00:00Z");
        timeService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        Subscription sub = subscriptionService.create("BASIC");
        String sid = sub.getId();

        Instant d1 = t0.plusSeconds(3600);
        Instant d2 = t0.plusSeconds(7200);
        // 乱序：先报晚的 d2，再报早的 d1（接收时刻推进，事件发生时刻保持业务时间）
        timeService.setClock(Clock.fixed(t0.plusSeconds(9000), ZoneOffset.UTC));
        usageService.report("evt-2", sid, 600, d2);
        usageService.report("evt-1", sid, 300, d1);
        log.info("[输入] 乱序上报 evt-1@{} qty=300 与 evt-2@{} qty=600", d1, d2);

        // 重复上报（同 eventId，且并发也只入库一次）
        expect(ErrorCode.DUPLICATE_USAGE_EVENT,
                () -> usageService.report("evt-1", sid, 300, d1));

        // 非法数量
        expect(ErrorCode.USAGE_EVENT_BAD_QUANTITY,
                () -> usageService.report("evt-bad", sid, 0, d2));
        Bill bill1 = billingService.generateBill(sid, 0);
        log.info("[第一次聚合] 总用量=900 配额=1000 超额=0 -> 总额={}分 明细={}",
                bill1.getTotalCents(), bill1.getLineItems());
        assertEquals(1000, bill1.getTotalCents());

        // 迟到补报：仍在同一账期，账单 DRAFT 可重算，聚合口径稳定
        Instant d3 = t0.plusSeconds(1800);
        timeService.setClock(Clock.fixed(t0.plusSeconds(20_000), ZoneOffset.UTC));
        usageService.report("evt-late", sid, 400, d3);
        log.info("[输入] 迟到事件 evt-late@{} qty=400（账期内，账单未确认）", d3);

        Bill bill2 = billingService.generateBill(sid, 0);
        long itemSum = bill2.getLineItems().stream().mapToLong(i -> i.amountCents()).sum();
        log.info("[补报后聚合] 总用量=1300 配额=1000 超额=300@3分 -> 总额={}分 明细和={}分 明细={}",
                bill2.getTotalCents(), itemSum, bill2.getLineItems());
        // 1000 基础 + 300*3 超额 = 1900
        assertEquals(1900, bill2.getTotalCents());
        assertEquals(itemSum, bill2.getTotalCents());

        // 再算一次：幂等稳定
        Bill bill3 = billingService.generateBill(sid, 0);
        assertEquals(bill2.getTotalCents(), bill3.getTotalCents());
        assertEquals(bill2.getLineItems().size(), bill3.getLineItems().size());

        // 确认账单（网关默认成功）
        billingService.confirmAndCollect(bill2.getId(), "key-1");
        // 确认后补报：可区分的拒绝（事件时刻仍在账期内，仅因账单已关闭）
        expect(ErrorCode.USAGE_LATE_PERIOD_CLOSED,
                () -> usageService.report("evt-after-close", sid, 100,
                        t0.plusSeconds(10_000)));
        log.info("[判定] 已确认账期补报被拒绝 USAGE_LATE_PERIOD_CLOSED（不静默丢弃）");

        // 已确认账单禁止重算
        expect(ErrorCode.PERIOD_ALREADY_CLOSED,
                () -> billingService.generateBill(sid, 0));
    }

    private void expect(ErrorCode code, Runnable r) {
        try {
            r.run();
            throw new AssertionError("期望抛出 " + code);
        } catch (BillingException e) {
            log.info("[拒绝校验] 期望={} 实际={} msg={}", code, e.getErrorCode(), e.getMessage());
            assertEquals(code, e.getErrorCode());
        }
    }
}
