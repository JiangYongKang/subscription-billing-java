package com.github.highcumontoa.subscriptionbillingjava.service.gateway;

/**
 * 支付网关抽象（仅本地模拟实现，不依赖任何外部服务）。
 */
public interface PaymentGateway {

    PaymentResult charge(String billId, long amountCents, int attemptNo);
}
