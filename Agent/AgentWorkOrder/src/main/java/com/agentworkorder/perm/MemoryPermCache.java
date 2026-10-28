package com.agentworkorder.perm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内权限缓存（M2 行为，M5 保留用于回滚 / 无 Redis 开发）。
 *
 * <p>单实例、重启即丢；只作为 {@code agent.perm.cache-store=memory} 时的回退。
 */
@Component
@ConditionalOnProperty(name = "agent.perm.cache-store", havingValue = "memory", matchIfMissing = true)
public class MemoryPermCache implements PermCache {

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public UserPerms get(String tokenHash) {
        Cached cached = cache.get(tokenHash);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            cache.remove(tokenHash);
            return null;
        }
        return cached.perms();
    }

    @Override
    public void put(String tokenHash, UserPerms perms, Duration ttl) {
        cache.put(tokenHash, new Cached(perms, System.currentTimeMillis() + ttl.toMillis()));
    }

    private record Cached(UserPerms perms, long expiresAtMillis) {
    }
}
