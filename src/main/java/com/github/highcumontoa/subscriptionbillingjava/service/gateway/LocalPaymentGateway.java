package com.github.highcumontoa.subscriptionbillingjava.service.gateway;

import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 本地支付网关：不调用任何外部服务。可按账单预置成功/失败序列（线程安全、逐条消费），
 * 未预置时默认成功；也可用 setDefaultFailUntilSuccess 模拟“前 N 次失败之后成功”。
 */
@Component
public class LocalPaymentGateway implements PaymentGateway {

    private final Map<String, Deque<Boolean>> presets = new ConcurrentHashMap<>();
    private volatile int defaultFailUntilSuccess = 0;

    @Override
    public PaymentResult charge(String billId, long amountCents, int attemptNo) {
        Deque<Boolean> queue = presets.get(billId);
        if (queue != null) {
            Boolean preset = queue.pollFirst();
            if (preset != null) {
                return preset ? PaymentResult.ok()
                        : PaymentResult.fail("INSUFFICIENT_FUNDS");
            }
        }
        if (attemptNo <= defaultFailUntilSuccess) {
            return PaymentResult.fail("INSUFFICIENT_FUNDS");
        }
        return PaymentResult.ok();
    }

    /** 预置某账单的扣款结果序列，例如 fail, fail, success。 */
    public void preset(String billId, Deque<Boolean> outcomes) {
        presets.put(billId, new ConcurrentLinkedDeque<>(outcomes));
    }

    public void preset(String billId, Boolean... outcomes) {
        Deque<Boolean> q = new ConcurrentLinkedDeque<>();
        for (Boolean o : outcomes) {
            q.add(o);
        }
        presets.put(billId, q);
    }

    public void setDefaultFailUntilSuccess(int failCount) {
        this.defaultFailUntilSuccess = Math.max(0, failCount);
    }

    public void clear() {
        presets.clear();
        defaultFailUntilSuccess = 0;
    }
}
