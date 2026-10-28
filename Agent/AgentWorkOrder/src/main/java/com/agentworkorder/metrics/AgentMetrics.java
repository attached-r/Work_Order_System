package com.agentworkorder.metrics;

import com.agentworkorder.resilience.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 的关键指标（M5 §9.5）。
 *
 * <p>只记录「能指导排障/告警」的少量指标，避免把指标做成第二套日志：
 * <ul>
 *   <li>{@code agent.sse.errors}：SSE 错误码分布；</li>
 *   <li>{@code agent.circuit.open}：当前哪个下游被熔断；</li>
 *   <li>{@code agent.circuit.rejected / failures}：熔断快速失败与真实失败次数；</li>
 *   <li>{@code agent.write.actions}：写操作预览/确认的成功失败分布。</li>
 * </ul>
 *
 * <p>标签一律用固定枚举值（resource/action/result/code），
 * <b>不要</b>把 sessionId、userId、traceId 放进标签，否则时序数量会爆炸。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一次 SSE 错误；按错误码分开，便于区分 502 / 503 / 504。 */
    public void sseError(int code) {
        registry.counter("agent.sse.errors", "code", String.valueOf(code)).increment();
    }

    /** 熔断器直接拒绝（请求没有真正发出去）。 */
    public void circuitRejected(String resource) {
        registry.counter("agent.circuit.rejected", "resource", resource).increment();
    }

    /** 下游真实调用失败（超时 / 连接断 / 5xx）。 */
    public void circuitFailure(String resource) {
        registry.counter("agent.circuit.failures", "resource", resource).increment();
    }

    /**
     * 注册熔断状态 Gauge：1=OPEN，0=CLOSED/HALF_OPEN。
     *
     * <p>同一个 resource 只应注册一次；由 {@code ResilienceGuard} 在创建熔断器时调用。
     */
    public void registerCircuitBreaker(String resource, CircuitBreaker breaker) {
        Gauge.builder("agent.circuit.open", breaker, b -> b.isOpen() ? 1.0 : 0.0)
                .tags("resource", resource)
                .register(registry);
    }

    /** 写操作计数：action=REVIEW/...，mode=preview/confirm，result=SUCCESS/ERROR。 */
    public void writeAction(String action, String mode, String result) {
        registry.counter("agent.write.actions",
                "action", action,
                "mode", mode,
                "result", result).increment();
    }

    /** 审计写入失败次数；审计失败不影响主流程，但需要告警。 */
    public void auditFailure() {
        registry.counter("agent.audit.failures").increment();
    }
}
