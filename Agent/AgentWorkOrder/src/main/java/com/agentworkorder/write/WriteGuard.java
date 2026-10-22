package com.agentworkorder.write;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每轮对话的写操作限流（M4）。
 * <p>
 * 模型可能在一轮回复里连续调用多个工具；只读无所谓，写操作必须限流，
 * 否则用户来不及看到第一张单的结果，下一张单已经被改了。
 * 以 {@code traceId}（一次 HTTP 对话请求一个）为键，只统计<b>真实提交</b>，预览不计。
 * 条目 10 分钟后清理，避免进程内无界增长。
 */
@Component
public class WriteGuard {

    /** 计数条目存活时间：一轮对话远短于 10 分钟，超过即可安全丢弃 */
    private static final long ENTRY_TTL_MILLIS = 10 * 60 * 1000L;

    private final WriteProperties properties;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public WriteGuard(WriteProperties properties) {
        this.properties = properties;
    }

    /**
     * 检查本轮是否还能写；能写则计数 +1，不能写抛 409。
     *
     * @param traceId 本轮追踪 id；缺失时用固定键，等价于所有无追踪调用共享一个额度
     */
    public void checkAndCount(String traceId) {
        int max = properties.getMaxActionsPerTurn();
        if (max <= 0) {
            throw new WriteRejectedException(409,
                    "写操作已禁用（agent.write.max-actions-per-turn=0），当前仅允许预览");
        }

        cleanupExpired();
        String key = StringUtils.hasText(traceId) ? traceId : "no-trace";
        Counter counter = counters.computeIfAbsent(key, k -> new Counter(System.currentTimeMillis()));

        // 先自增再比较；超限则回滚，保证被拒绝的调用不占额度
        if (counter.count.incrementAndGet() > max) {
            counter.count.decrementAndGet();
            throw new WriteRejectedException(409,
                    "本轮对话已执行过一次写操作，请另起一轮确认下一个动作");
        }
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis > ENTRY_TTL_MILLIS);
    }

    /** 单轮计数器：创建时刻用于清理，count 为已执行的写次数 */
    private static final class Counter {
        private final long createdAtMillis;
        private final AtomicInteger count = new AtomicInteger();

        private Counter(long createdAtMillis) {
            this.createdAtMillis = createdAtMillis;
        }
    }
}
