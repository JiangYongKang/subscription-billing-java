package com.github.highcumontoa.subscriptionbillingjava.service.gateway;

/**
 * 本地模拟支付结果。
 */
public record PaymentResult(boolean success, String failureCode) {

    public static PaymentResult ok() {
        return new PaymentResult(true, null);
    }

    public static PaymentResult fail(String code) {
        return new PaymentResult(false, code);
    }
}
