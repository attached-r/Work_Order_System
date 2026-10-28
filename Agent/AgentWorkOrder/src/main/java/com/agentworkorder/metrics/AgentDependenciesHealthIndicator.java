package com.agentworkorder.metrics;

import com.agentworkorder.resilience.ResilienceGuard;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 依赖健康（M5 §9.6）：熔断状态 + Redis 连通性。
 *
 * <p>返回自定义状态 {@code DEGRADED}（HTTP 仍 200），表示「能服务、但部分能力降级」。
 * 不要把它直接放进 readiness：Redis 是可降级依赖，Redis 挂掉时如果把所有实例摘流，
 * 反而会让整个服务不可用。
 */
@Component
public class AgentDependenciesHealthIndicator implements HealthIndicator {

    private final ResilienceGuard resilience;
    private final StringRedisTemplate redis;

    public AgentDependenciesHealthIndicator(ResilienceGuard resilience, StringRedisTemplate redis) {
        this.resilience = resilience;
        this.redis = redis;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();

        Map<String, String> circuits = resilience.states();
        details.put("circuits", circuits);

        boolean redisUp;
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            redisUp = "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            redisUp = false;
        }
        details.put("redis", redisUp ? "UP" : "DOWN");

        boolean degraded = !redisUp || circuits.containsValue("OPEN");
        Health.Builder builder = degraded ? Health.status("DEGRADED") : Health.up();
        return builder.withDetails(details).build();
    }
}
