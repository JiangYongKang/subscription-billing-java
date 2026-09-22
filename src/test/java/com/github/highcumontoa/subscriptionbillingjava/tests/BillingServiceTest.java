package com.github.highcumontoa.subscriptionbillingjava.tests;

import com.github.highcumontoa.subscriptionbillingjava.billing.Invoice;
import com.github.highcumontoa.subscriptionbillingjava.bootstrap.BillingTestFixtures;
import com.github.highcumontoa.subscriptionbillingjava.billing.InvoiceStatus;
import com.github.highcumontoa.subscriptionbillingjava.exception.BillingException;
import com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BillingServiceTest {

    private BillingTestFixtures fx() {
        return BillingTestFixtures.create();
    }

    /** 整月 BASIC：用量80在包含额度100内，账单=月费1000，无用量费。 */
    @Test
    @DisplayName("账单合并: 用量在包含额度内时 total=planFee，守恒 total=plan+usage")
    void invoice_withinQuota_planFeeOnly() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        fx.usageService.report("EVT-1", sub.getId(), 80L,
                sub.getPeriodStart() + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());

        System.out.println("[case] input BASIC used=80 state planFee=" + inv.getPlanFeeCents()
                + "c usageFee=" + inv.getUsageFeeCents() + "c total=" + inv.getTotalCents() + "c");
        assertEquals(1000L, inv.getPlanFeeCents());
        assertEquals(0L, inv.getUsageFeeCents());
        assertEquals(1000L, inv.getTotalCents());
        assertEquals(inv.getTotalCents(),
                inv.getPlanFeeCents() + inv.getUsageFeeCents(), "账单总额守恒");
    }

    /** 整月 BASIC：用量250 -> 超用150，阶梯 100*5+50*3=650，total=1650。 */
    @Test
    @DisplayName("阶梯用量费: BASIC 用250超150 -> 100*5+50*3=650，total=1000+650=1650")
    void invoice_overage_tieredFee() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        fx.usageService.report("EVT-1", sub.getId(), 250L,
                sub.getPeriodStart() + fx.days(5));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());

        System.out.println("[case] input BASIC used=250 overage=150 planFee="
                + inv.getPlanFeeCents() + "c usageFee=" + inv.getUsageFeeCents()
                + "c total=" + inv.getTotalCents() + "c");
        assertEquals(1000L, inv.getPlanFeeCents());
        assertEquals(650L, inv.getUsageFeeCents());
        assertEquals(1650L, inv.getTotalCents());
    }

    /** 第15天 BASIC->PRO：planFee=500+1500=2000；额度=50+250=300；用量350超50按PRO档=4*50=200。 */
    @Test
    @DisplayName("周期中升级: 半月比例月费之和 + 比例额度 + 阶梯超用，费用可核对")
    void invoice_midPeriodUpgrade_proratedAndConserved() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        long t15 = sub.getPeriodStart() + fx.days(15);
        fx.clock.set(t15);
        fx.subscriptionService.changePlan(sub.getId(), "PRO", t15, sub.getVersion());
        fx.clock.set(t15 + fx.days(1));
        fx.usageService.report("EVT-1", sub.getId(), 350L, t15 + fx.days(1));
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());

        System.out.println("[case] input BASIC->PRO@15d used=350 planFee="
                + inv.getPlanFeeCents() + "c (expect 2000) usageFee=" + inv.getUsageFeeCents()
                + "c (expect 200) total=" + inv.getTotalCents() + "c");
        assertEquals(2000L, inv.getPlanFeeCents(), "500+1500 半月比例月费");
        assertEquals(200L, inv.getUsageFeeCents(), "超用50按PRO首档4分");
        assertEquals(2200L, inv.getTotalCents());
        // 结构化分段明细：两段费用之和等于套餐费，可逐段核对
        System.out.println("[case] lineItems=" + inv.getLineItems());
        assertEquals(2, inv.getLineItems().size());
        assertEquals("BASIC", inv.getLineItems().get(0).planCode());
        assertEquals(500L, inv.getLineItems().get(0).feeCents());
        assertEquals("PRO", inv.getLineItems().get(1).planCode());
        assertEquals(1500L, inv.getLineItems().get(1).feeCents());
        assertEquals(inv.getPlanFeeCents(),
                inv.getLineItems().stream().mapToLong(Invoice.LineItem::feeCents).sum());
    }

    @Test
    @DisplayName("同账期唯一: 重复生成返回同一账单且金额冻结，账期未结束拒绝出账")
    void invoice_periodUniqueness_andNotBeforePeriodEnd() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice i1 = fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());
        Invoice i2 = fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());
        System.out.println("[case] decision sameInvoice=" + i1.getId().equals(i2.getId()));
        assertSame(i1, i2, "同一账期只允许一张账单");

        // 新订阅：账期未结束出账应被拒绝
        var sub2 = fx.subscriptionService.create("PRO", fx.clock.nowMillis());
        BillingException ex = assertThrows(BillingException.class,
                () -> fx.billingService.generateInvoice(sub2.getId(), sub2.getPeriodStart()));
        System.out.println("[case] decision earlyGenerate=" + ex.getCode());
        assertEquals(ErrorCode.INVOICE_PERIOD_CONFLICT, ex.getCode());
    }

    @Test
    @DisplayName("确认幂等: 重复确认 PAID 账单不二次扣款，金额保持不变")
    void confirmPayment_idempotent_noDoubleCharge() {
        var fx = fx();
        var sub = fx.subscriptionService.create("BASIC", fx.clock.nowMillis());
        fx.clock.set(sub.getPeriodEnd() + 1L);
        Invoice inv = fx.billingService.generateInvoice(sub.getId(), sub.getPeriodStart());

        Invoice first = fx.billingService.confirmPayment(inv.getId());
        Invoice second = fx.billingService.confirmPayment(inv.getId());
        Invoice third = fx.billingService.confirmPayment(inv.getId());

        int chargeCount = fx.gateway.recordsOf(inv.getId()).size();
        System.out.println("[case] input confirm x3 state status=" + third.getStatus()
                + " attempt=" + third.getAttempt() + " total=" + third.getTotalCents()
                + "c decision gatewayCharges=" + chargeCount + " records="
                + fx.gateway.recordsOf(inv.getId()));
        assertEquals(InvoiceStatus.PAID, first.getStatus());
        assertEquals(InvoiceStatus.PAID, second.getStatus());
        assertEquals(InvoiceStatus.PAID, third.getStatus());
        assertEquals(1, chargeCount, "重复确认只能产生一次真实扣款");
        assertEquals(1, third.getAttempt(), "尝试序号不重复累加");
        assertEquals(1000L, third.getTotalCents(), "账单金额冻结");
    }
}
