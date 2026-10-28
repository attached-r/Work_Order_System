package com.agentworkorder.write;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内写令牌存储（M4 行为，M5 保留用于回滚 / 无 Redis 开发）。
 *
 * <p>单实例、重启即丢；多实例部署时请用 {@code agent.write.token-store=redis}。
 */
@Component
@ConditionalOnProperty(name = "agent.write.token-store", havingValue = "memory", matchIfMissing = true)
public class MemoryPendingWriteStore implements PendingWriteStore {

    private final ConcurrentHashMap<String, PendingWrite> pending = new ConcurrentHashMap<>();

    @Override
    public void save(PendingWrite write, Duration ttl) {
        // ttl 由 PendingWrite.expiresAtMillis 自己带着；这里顺手清一下过期项
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
        pending.put(write.token(), write);
    }

    @Override
    public PendingWrite consume(String token) {
        return pending.remove(token);
    }

    @Override
    public boolean isFull(int maxEntries) {
        // 先清过期项，避免「表里全是过期令牌却拒绝新预览」
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
        return pending.size() >= maxEntries;
    }
}
