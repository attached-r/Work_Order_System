package com.agentworkorder.write;

import com.agentworkorder.redis.JsonCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版写令牌存储（M5 §8.3，默认）。
 *
 * <p>key = {@code agent:write:pending:<token>}，value = {@link PendingWrite} JSON，TTL 由 Redis 负责。
 * Redis 故障时<b>写路径 fail-closed</b>：无法保存/消费令牌就拒绝写，绝不退回进程内 Map
 * （否则会出现「A 实例签发、B 实例不认」的分裂）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.write.token-store", havingValue = "redis")
public class RedisPendingWriteStore implements PendingWriteStore {

    private final StringRedisTemplate redis;
    private final JsonCodec codec;
    private final String keyPrefix;

    public RedisPendingWriteStore(StringRedisTemplate redis, JsonCodec codec, WriteProperties properties) {
        this.redis = redis;
        this.codec = codec;
        this.keyPrefix = properties.getTokenKeyPrefix();
    }

    @Override
    public void save(PendingWrite write, Duration ttl) {
        try {
            redis.opsForValue().set(key(write.token()), codec.toJson(write), ttl);
        } catch (Exception e) {
            log.warn("保存写确认令牌失败：{}", e.toString());
            throw new WriteRejectedException(503, "写操作暂不可用，请稍后重试");
        }
    }

    @Override
    public PendingWrite consume(String token) {
        try {
            // GETDEL：原子「取出并删除」，Redis 6.2+；天然防重放
            String json = redis.opsForValue().getAndDelete(key(token));
            return json == null ? null : codec.fromJson(json, PendingWrite.class);
        } catch (Exception e) {
            log.warn("消费写确认令牌失败：{}", e.toString());
            throw new WriteRejectedException(503, "写操作暂不可用，请稍后重试");
        }
    }

    private String key(String token) {
        return keyPrefix + token;
    }
}
