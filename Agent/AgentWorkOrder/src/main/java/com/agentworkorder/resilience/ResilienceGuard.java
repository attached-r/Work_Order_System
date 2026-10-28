package com.agentworkorder.resilience;

import com.agentworkorder.config.ResilienceProperties;
import com.agentworkorder.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 熔断 + fallback 的统一入口（M5 §7.5）。
 *
 * <p>为什么区分 whenOpen / whenFailed：
 * <ul>
 *   <li><b>whenOpen</b>：熔断器直接拒绝，请求<b>一定没有发出去</b>，
 *       写操作可以告诉用户「未提交」；</li>
 *   <li><b>whenFailed</b>：请求发出后异常（超时/连接断），写操作<b>可能已经执行</b>，
 *       只能告诉用户「结果未知，请先查询」。</li>
 * </ul>
 * 这个区别对读操作无所谓，对写操作非常关键（M4 §7.4）。
 */
@Slf4j
@Component
public class ResilienceGuard {

    private final ResilienceProperties properties;
    private final AgentMetrics metrics;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ResilienceGuard(ResilienceProperties properties, AgentMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 包裹一次同步调用。
     *
     * @param resource   资源名（project-one / rag）
     * @param action     真正调用
     * @param whenOpen   熔断拒绝时的返回值（请求没发出去）
     * @param whenFailed 调用抛异常时的返回值（结果可能未知）
     */
    public <T> T call(String resource, Supplier<T> action,
                      Supplier<T> whenOpen, Function<RuntimeException, T> whenFailed) {
        if (!properties.isEnabled()) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                return whenFailed.apply(e);
            }
        }

        CircuitBreaker breaker = breaker(resource);
        if (!breaker.tryAcquire()) {
            metrics.circuitRejected(resource);
            log.warn("熔断中，快速失败：resource={}", resource);
            return whenOpen.get();
        }

        try {
            T result = action.get();
            breaker.recordSuccess();
            return result;
        } catch (RuntimeException e) {
            breaker.recordFailure();
            metrics.circuitFailure(resource);
            log.warn("下游调用失败：resource={} err={}", resource, e.toString());
            return whenFailed.apply(e);
        }
    }

    /**
     * 包裹一条响应式流（LLM 用）。
     *
     * <p>成功判定 = 流正常结束；中途报错算失败；客户端主动取消不算失败。
     */
    public <T> Flux<T> guardFlux(String resource, Flux<T> upstream) {
        if (!properties.isEnabled()) {
            return upstream;
        }

        CircuitBreaker breaker = breaker(resource);
        return Flux.defer(() -> {
            if (!breaker.tryAcquire()) {
                metrics.circuitRejected(resource);
                return Flux.error(new CircuitOpenException(resource));
            }
            return upstream
                    .doOnComplete(breaker::recordSuccess)
                    .doOnError(e -> {
                        breaker.recordFailure();
                        metrics.circuitFailure(resource);
                    });
        });
    }

    /** 健康检查 / 指标用：资源名 → 当前状态。 */
    public Map<String, String> states() {
        Map<String, String> result = new LinkedHashMap<>();
        breakers.forEach((name, breaker) -> result.put(name, breaker.state().name()));
        return result;
    }

    private CircuitBreaker breaker(String resource) {
        return breakers.computeIfAbsent(resource, name -> {
            CircuitBreaker created = new CircuitBreaker(
                    name,
                    properties.getFailureThreshold(),
                    properties.getOpenSeconds(),
                    properties.getHalfOpenMaxCalls());
            metrics.registerCircuitBreaker(name, created);
            return created;
        });
    }
}
