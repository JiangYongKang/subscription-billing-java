package com.github.highcumontoa.subscriptionbillingjava.demo;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.domain.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;

/**
 * 本地确定性端到端演示（不依赖 Spring 启动与任何外部资源）。
 *
 * <p>运行：mvn -q exec:java
 * -Dexec.mainClass=com.github.highcumontoa.subscriptionbillingjava.demo.LocalBillingDemo
 *
 * <p>演示链路：创建 -> 周期中升级（比例费）-> 重复/乱序用量 -> 出账合并与总额守恒
 * -> 收款失败入催收 -> 提前重试被拒 -> 到期重试成功 -> 重复确认不二次扣款；
 * 另附一条“重试耗尽 -> FAILED”链路。
 */
public final class LocalBillingDemo {

    public static void main(String[] args) {
        var fx = BillingTestFixtures.create();
        banner("1) 创建订阅 BASIC");
        Subscription sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        System.out.println("subscriptionId=" + sub.getId() + " version=" + sub.getVersion()
                + " period=[" + sub.getPeriodStart() + "," + sub.getPeriodEnd() + ")");

        banner("2) 第15天升级 PRO（周期中变更 -> 半月比例分段）");
        long t15 = sub.getPeriodStart() + fx.days(15);
        fx.clock.set(t15);
        fx.subscriptionService.changePlan(sub.getId(), "PRO", t15, sub.getVersion());
        System.out.println("segments=" + fx.subscriptionRepository
                .findById(sub.getId()).getSegments());

        banner("3) 用量上报：重复事件被拒、乱序/迟到事件正常归并");
        long t5 = sub.getPeriodStart() + fx.days(5);
        long t16 = sub.getPeriodStart() + fx.days(16);
        fx.clock.set(t16);
        fx.usageService.report("EVT-LATE", sub.getId(), 350L, t5); // 迟到补报
        fx.usageService.report("EVT-A", sub.getId(), 60L, t16);
        try {
            fx.usageService.report("EVT-A", sub.getId(), 60L, t16); // 重复
        } catch (BillingException e) {
            System.out.println("重复上报被拒绝(预期): " + e.getCode());
        }
        System.out.println("账期聚合用量=" + fx.usageService.aggregate(
                sub.getId(), sub.getPeriodStart(), sub.getPeriodEnd()));

        banner("4) 账期结束出账（套餐比例费 + 阶梯用量费，总额守恒）");
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());
        System.out.printf("invoice=%s planFee=%dc usageFee=%dc total=%dc (守恒=%s)%n",
                inv.getId(), inv.getPlanFeeCents(), inv.getUsageFeeCents(),
                inv.getTotalCents(),
                inv.getTotalCents() == inv.getPlanFeeCents() + inv.getUsageFeeCents());

        banner("5) 首次扣款失败 -> 进入催收；未到期重试被拒");
        fx.gateway.prime(inv.getId(), false, "INSUFFICIENT_FUNDS");
        fx.gateway.prime(inv.getId(), true, "OK_AFTER_FUNDS");
        Invoice dunning = fx.billingService.confirmPayment(inv.getId());
        System.out.println("status=" + dunning.getStatus() + " attempt=" + dunning.getAttempt()
                + " nextRetryAt=" + dunning.getNextRetryAt());
        try {
            fx.dunningService.processRetry(inv.getId());
        } catch (BillingException e) {
            System.out.println("未到期重试被拒(预期): " + e.getCode());
        }

        banner("6) 推进时钟到重试到期 -> 扣款成功 PAID；重复确认不二次扣款");
        fx.clock.advance(fx.days(1));
        Invoice paid = fx.dunningService.processRetry(inv.getId());
        System.out.println("status=" + paid.getStatus() + " attempt=" + paid.getAttempt());
        fx.billingService.confirmPayment(inv.getId());
        fx.dunningService.processRetry(inv.getId());
        System.out.println("真实扣款次数(预期2): " + fx.gateway.recordsOf(inv.getId()).size()
                + " records=" + fx.gateway.recordsOf(inv.getId()));

        banner("7) 另一订阅：持续失败直到重试耗尽 -> FAILED");
        var fx2 = BillingTestFixtures.create();
        Subscription sub2 = fx2.subscriptionService.create("PRO", fx2.clock.nowMillis());
        fx2.clock.set(sub2.getPeriodEnd() + 1L);
        Invoice inv2 = fx2.billingService.generateInvoice(sub2.getId(), sub2.getPeriodStart());
        fx2.gateway.prime(inv2.getId(), false, "CARD_EXPIRED");
        fx2.billingService.confirmPayment(inv2.getId());
        int guard = 0;
        while (fx2.billingService.getInvoice(inv2.getId()).getStatus() != InvoiceStatus.FAILED
                && guard++ < 10) {
            long next = fx2.billingService.getInvoice(inv2.getId()).getNextRetryAt();
            fx2.clock.set(next + 1L);
            fx2.dunningService.processRetry(inv2.getId());
        }
        Invoice terminal = fx2.billingService.getInvoice(inv2.getId());
        System.out.println("final status=" + terminal.getStatus()
                + " attempts=" + terminal.getAttempt()
                + " charges=" + fx2.gateway.recordsOf(inv2.getId()).size());

        System.out.println();
        System.out.println("=== DEMO DONE: 全部结论本地确定、可复现 ===");
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
    }
}
