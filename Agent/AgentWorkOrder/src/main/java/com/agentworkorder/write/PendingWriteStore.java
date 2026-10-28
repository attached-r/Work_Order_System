package com.agentworkorder.write;

import java.time.Duration;

/**
 * 写确认令牌的存储端口（M5 §8.3）。
 *
 * <p>语义要求：
 * <ul>
 *   <li>save：带 TTL 写入；</li>
 *   <li>consume：<b>原子地</b>取出并删除（一个令牌只能用一次）；</li>
 *   <li>isFull：仅进程内实现需要；Redis 靠 TTL 自然收敛。</li>
 * </ul>
 */
public interface PendingWriteStore {

    void save(PendingWrite pending, Duration ttl);

    /** 原子取出并删除；不存在返回 null。 */
    PendingWrite consume(String token);

    default boolean isFull(int maxEntries) {
        return false;
    }
}
