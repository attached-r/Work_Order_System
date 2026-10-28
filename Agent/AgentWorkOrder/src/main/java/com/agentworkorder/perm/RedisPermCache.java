package com.agentworkorder.perm;

import com.agentworkorder.config.PermProperties;
import com.agentworkorder.redis.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版权限缓存（M5 §8.1，默认）。
 *
 * <p>key = {@code agent:perm:<sha256(token)>}，value = {@link UserPerms} 的 JSON，
 * TTL 由 Redis 负责。Redis 故障时：
 * <ul>
 *   <li>读失败 → 返回 null（miss），让 PermService 回源项目一；</li>
 *   <li>写失败 → 只告警，不影响本轮对话。</li>
 * </ul>
 * 注意：缓存失败不能变成「放行」，真正的权限仍由项目一裁决。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.perm.cache-store", havingValue = "redis")
public class RedisPermCache implements PermCache {

    private final StringRedisTemplate redis;
    private final JsonCodec codec;
    private final String keyPrefix;

    public RedisPermCache(StringRedisTemplate redis, JsonCodec codec, PermProperties properties) {
        this.redis = redis;
        this.codec = codec;
        this.keyPrefix = properties.getKeyPrefix();
    }

    @Override
    public UserPerms get(String tokenHash) {
        try {
            String json = redis.opsForValue().get(keyPrefix + tokenHash);
            return json == null ? null : codec.fromJson(json, UserPerms.class);
        } catch (Exception e) {
            // 缓存故障 = miss，让调用方回源；不能因为缓存挂了就放行权限
            log.warn("读取 perms 缓存失败，回源项目一：{}", e.toString());
            return null;
        }
    }

    @Override
    public void put(String tokenHash, UserPerms perms, Duration ttl) {
        try {
            redis.opsForValue().set(keyPrefix + tokenHash, codec.toJson(perms), ttl);
        } catch (Exception e) {
            log.warn("写入 perms 缓存失败（不影响本轮）：{}", e.toString());
        }
    }
}
