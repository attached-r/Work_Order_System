package com.agentworkorder.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 指标门面（M5 的最小版本）。
 *
 * <p>当前项目还没有引入 {@code spring-boot-starter-actuator}，所以先用
 * {@link AtomicLong} + 日志把「SSE 错误」这类计数跑起来，保证 SSE 链路能编译、能观察。
 * 等做 M5 §9.5 的指标时，把内部实现替换成 Micrometer 的 {@code MeterRegistry} 即可，
 * 调用方（{@code AgentSseEvents}）不用改。
 */
@Slf4j
@Component
public class AgentMetrics {

    private final AtomicLong sseErrors = new AtomicLong();

    /** 记录一次 SSE 错误；按错误码分开计数，便于排查是 502 还是 504。 */
    public void sseError(int code) {
        long total = sseErrors.incrementAndGet();
        log.info("SSE 错误计数：code={} total={}", code, total);
    }
}
