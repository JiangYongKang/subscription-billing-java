package com.github.highcumontoa.subscriptionbillingjava.store.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按业务键分段的锁，保证同一订阅/账单的变更串行化，避免重复计费与丢失更新。
 */
public class LockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock get(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }
}
