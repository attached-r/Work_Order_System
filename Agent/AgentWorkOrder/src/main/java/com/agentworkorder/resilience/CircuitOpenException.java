package com.agentworkorder.resilience;

/**
 * 熔断器打开时抛出：请求没有被真正发出去。
 *
 * <p>SSE 层把它映射成 {@code 503 + retryable=true}；
 * 写操作层可以据此告诉用户「未提交」，而不是「结果未知」。
 */
public class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String resource) {
        super("circuit open: " + resource);
    }
}
