package com.github.highcumontoa.subscriptionbillingjava.repo;

import com.github.highcumontoa.subscriptionbillingjava.domain.PlanChange;
import com.github.highcumontoa.subscriptionbillingjava.domain.Subscription;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 订阅本地存储。
 * <ul>
 *   <li>每个订阅 ID 对应一把锁（{@link #withLock}），同一订阅的并发变更被串行化，
 *       从根上消除“重复计费 / 丢失更新”的数据竞争窗口；</li>
 *   <li>变更历史（PlanChange）只增不改，按追加顺序保存；</li>
 *   <li>findById 返回的是存储对象本身，调用方须在 withLock 临界区内读取/修改/保存，
 *       版本号检查作为第二道防线（乐观锁）。</li>
 * </ul>
 */
public class SubscriptionRepository {

    private final Map<String, Subscription> store = new ConcurrentHashMap<>();
    private final Map<String, List<PlanChange>> changes = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public Subscription findById(String id) {
        return store.get(id);
    }

    public Subscription requireById(String id) {
        Subscription s = store.get(id);
        if (s == null) {
            throw new com.github.highcumontoa.subscriptionbillingjava.exception.BillingException(
                    com.github.highcumontoa.subscriptionbillingjava.exception.ErrorCode.SUBSCRIPTION_NOT_FOUND,
                    "订阅不存在: " + id);
        }
        return s;
    }

    public void save(Subscription subscription) {
        store.put(subscription.getId(), subscription);
    }

    public void appendChange(PlanChange change) {
        changes.computeIfAbsent(change.subscriptionId(), k -> new CopyOnWriteArrayList<>())
                .add(change);
    }

    public List<PlanChange> changesOf(String subscriptionId) {
        return List.copyOf(changes.getOrDefault(subscriptionId, List.of()));
    }

    /** 对单个订阅加监视器锁并执行动作（同一订阅的并发变更串行化入口）。 */
    public <T> T withLock(String subscriptionId, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(subscriptionId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
