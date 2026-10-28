package com.agentworkorder.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断器状态机单测（P0）。
 *
 * <p>时间处理：{@link CircuitBreaker} 用 {@code System.nanoTime()} 判断打开时长，
 * 这里把 {@code openSeconds} 设为 1，并 sleep 1.1 秒进入半开；
 * 不把生产默认的 30 秒带进测试，避免测试套件变慢。
 */
class CircuitBreakerTest {

    @Test
    void shouldStayClosedBeforeFailureThreshold() {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, 1, 1);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.recordFailure();
        breaker.recordFailure();

        // 只失败 2 次，阈值是 3：仍然放行
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void shouldOpenAfterConsecutiveFailures() {
        CircuitBreaker breaker = new CircuitBreaker("test", 2, 1, 1);

        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void halfOpenSuccessShouldCloseBreaker() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("test", 1, 1, 1);

        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(1100);

        // 打开 1 秒后进入半开，放行一个试探
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void halfOpenFailureShouldReopenBreaker() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("test", 1, 1, 1);

        breaker.tryAcquire();
        breaker.recordFailure();

        Thread.sleep(1100);

        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void halfOpenShouldRespectMaxCalls() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("test", 1, 1, 1);

        breaker.tryAcquire();
        breaker.recordFailure();

        Thread.sleep(1100);

        // 第一次试探放行
        assertThat(breaker.tryAcquire()).isTrue();
        // 第二次试探被拒绝，避免恢复瞬间把下游再打挂
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void successShouldResetFailureCount() {
        CircuitBreaker breaker = new CircuitBreaker("test", 2, 1, 1);

        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();

        // 成功会清零连续失败计数，所以此时还没到阈值
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
