package com.rj.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分布式锁行为参数,对应 application.yaml 中的 {@code redis.lock.*}(模块四)。
 * <p>
 * 抽出来的意义:<b>wait / lease 是「策略」不是「代码」</b>——调参不该改代码。
 * 三个场景的临界区耗时差异很大,故各自一套参数:
 * <ul>
 *   <li>编号生成:仅 {@code INCR + EXPIRE},毫秒级,wait/lease 都取小;</li>
 *   <li>资源申请:锁内有一次 JOIN 校验 + 一次事务写入,稍重,lease 取 10s;</li>
 *   <li>缓存重建:抢不到锁<b>不阻塞</b>,直接回源,故 wait 取 1s。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "redis.lock")
public class RedisLockProperties {

    /** 编号生成锁:最长等待秒数 */
    private long orderNoWaitSeconds = 2L;

    /** 编号生成锁:租约秒数 */
    private long orderNoLeaseSeconds = 5L;

    /** 资源申请锁:最长等待秒数 */
    private long resourceWaitSeconds = 3L;

    /** 资源申请锁:租约秒数 */
    private long resourceLeaseSeconds = 10L;

    /** 缓存重建锁:最长等待秒数(极短,抢不到即回源,不长等以免堆满线程池) */
    private long cacheRebuildWaitSeconds = 1L;

    /** 缓存重建锁:租约秒数 */
    private long cacheRebuildLeaseSeconds = 5L;
}
