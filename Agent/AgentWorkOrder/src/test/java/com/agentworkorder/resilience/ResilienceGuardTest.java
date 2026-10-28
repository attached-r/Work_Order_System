package com.agentworkorder.resilience;

import com.agentworkorder.config.ResilienceProperties;
import com.agentworkorder.metrics.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断门面 {@link ResilienceGuard} 的语义测试（P0 的配套）。
 *
 * <p>重点不是重复测 CircuitBreaker，而是锁住两种 fallback 的区别：
 * <ul>
 *   <li>{@code whenOpen}：请求一定没有发出去；</li>
 *   <li>{@code whenFailed}：请求发出后失败，结果可能未知。</li>
 * </ul>
 * 这个区别对写操作尤其重要（M4 §7.4：写路径不能用“未提交”冒充“结果未知”）。
 */
class ResilienceGuardTest {

    private ResilienceGuard guard;

    @BeforeEach
    void setUp() {
        ResilienceProperties properties = new ResilienceProperties();
        properties.setEnabled(true);
        properties.setFailureThreshold(1);
        properties.setOpenSeconds(1);
        properties.setHalfOpenMaxCalls(1);

        guard = new ResilienceGuard(properties, new AgentMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void callShouldReturnActionResultWhenDownstreamSucceeds() {
        String result = guard.call("test-success",
                () -> "ok",
                () -> "open",
                e -> "failed");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void callShouldUseWhenFailedWhenActionThrows() {
        String result = guard.call("test-failed",
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> "open",
                e -> "failed");

        assertThat(result).isEqualTo("failed");
    }

    @Test
    void openBreakerShouldUseWhenOpenAndNeverInvokeAction() {
        AtomicInteger invocations = new AtomicInteger();

        // 第一次：真实失败，熔断器打开
        String first = guard.call("test-open",
                () -> {
                    invocations.incrementAndGet();
                    throw new IllegalStateException("down");
                },
                () -> "open",
                e -> "failed");
        assertThat(first).isEqualTo("failed");
        assertThat(invocations.get()).isEqualTo(1);

        // 第二次：熔断中，直接走 whenOpen，不能再次调用 action
        String second = guard.call("test-open",
                () -> {
                    invocations.incrementAndGet();
                    return "should-not-run";
                },
                () -> "open",
                e -> "failed");

        assertThat(second).isEqualTo("open");
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void disabledGuardShouldOnlyUseWhenFailed() {
        ResilienceProperties disabled = new ResilienceProperties();
        disabled.setEnabled(false);
        ResilienceGuard disabledGuard =
                new ResilienceGuard(disabled, new AgentMetrics(new SimpleMeterRegistry()));

        String result = disabledGuard.call("test-disabled",
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> "open",
                e -> "failed");

        // 关闭熔断时仍然保留“失败降级”，只是不再快速拒绝
        assertThat(result).isEqualTo("failed");
    }

    @Test
    void guardFluxShouldPassThroughNormalStream() {
        assertThat(guard.guardFlux("test-flux-ok", Flux.just("a", "b"))
                .collectList()
                .block())
                .containsExactly("a", "b");
    }

    @Test
    void guardFluxShouldPropagateErrorAndOpenBreaker() {
        assertThatThrownBy(() -> guard.guardFlux("test-flux-error",
                Flux.error(new IllegalStateException("stream down")))
                .blockLast())
                .isInstanceOf(IllegalStateException.class);

        // 阈值是 1：上一条流失败后，下一次应直接 CircuitOpenException
        assertThatThrownBy(() -> guard.guardFlux("test-flux-error", Flux.just("x"))
                .blockLast())
                .isInstanceOf(CircuitOpenException.class);
    }
}
