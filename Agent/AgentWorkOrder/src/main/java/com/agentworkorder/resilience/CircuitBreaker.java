package com.agentworkorder.resilience;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 轻量熔断器（M5 §7.4）：CLOSED / OPEN / HALF_OPEN 三态。
 *
 * <p>为什么用 {@code synchronized} 而不是一组 Atomic：
 * 熔断判断是「读状态 + 改状态」的复合操作，用 CAS 写起来反而绕；
 * 调用频率是「每次下游请求一次」，临界区只有几个赋值，锁竞争可以忽略。
 *
 * <p>失败计数用「连续失败」而不是滑动窗口：实现简单、行为可预测；
 * 本项目需要防的是「下游整体挂掉后还一直打」，不是精确的失败率统计。
 */
@Slf4j
public class CircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;
    private final long openNanos;
    private final int halfOpenMaxCalls;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openUntilNanos = 0L;
    private int halfOpenCalls = 0;

    public CircuitBreaker(String name, int failureThreshold, int openSeconds, int halfOpenMaxCalls) {
        this.name = name;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openNanos = TimeUnit.SECONDS.toNanos(Math.max(1, openSeconds));
        this.halfOpenMaxCalls = Math.max(1, halfOpenMaxCalls);
    }

    /**
     * 尝试放行一次调用。
     *
     * @return true=可以调用；false=熔断中，调用方直接走 fallback
     */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                // 到了打开时长，放一个试探请求；试探成功才恢复 CLOSED
                if (System.nanoTime() >= openUntilNanos) {
                    state = State.HALF_OPEN;
                    halfOpenCalls = 1;
                    log.info("熔断器 {} 进入半开，放行试探请求", name);
                    return true;
                }
                return false;
            case HALF_OPEN:
                if (halfOpenCalls < halfOpenMaxCalls) {
                    halfOpenCalls++;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** 调用成功：无论之前什么状态，都恢复 CLOSED。 */
    public synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("熔断器 {} 试探成功，恢复 CLOSED", name);
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
        halfOpenCalls = 0;
    }

    /** 调用失败：CLOSED 下累计；HALF_OPEN 下直接重新打开。 */
    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            trip();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            trip();
        }
    }

    private void trip() {
        state = State.OPEN;
        openUntilNanos = System.nanoTime() + openNanos;
        halfOpenCalls = 0;
        consecutiveFailures = 0;
        log.warn("熔断器 {} 打开，{} 秒后允许试探",
                name, TimeUnit.NANOSECONDS.toSeconds(openNanos));
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isOpen() {
        return state == State.OPEN;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
