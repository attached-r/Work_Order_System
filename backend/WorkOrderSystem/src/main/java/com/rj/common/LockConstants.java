package com.rj.common;

/**
 * 分布式锁 Key 常量(模块四)。
 * <p>
 * 命名纪律:缓存键一律 {@code domain:entity:...},锁键一律 {@code lock:...}。
 * 两类键语义不同——一个是「数据」、一个是「状态」——前缀先分开,运维扫库或排查时一眼可分。
 * <p>
 * 与 {@link RedisConstants} 的分工:{@code RedisConstants} 管缓存与序号的 Key/过期时间,
 * 本类只管锁名。锁名放独立类,是为了避免「业务代码里散落 {@code "lock:xxx"} 魔法字符串」。
 */
public final class LockConstants {

    private LockConstants() {
    }

    /**
     * 工单编号生成锁:全局单锁(同一天内所有编号生成串行)。
     * <p>
     * 守护的临界区是「Redis {@code INCR} 日内序号 + 首次补 {@code EXPIRE}」这两步——
     * {@code INCR} 本身原子且唯一,锁的价值在于让这两步不可分割(否则首日第一次生成时,
     * 若在两步之间进程崩溃,会留下一个永不过期的序号键)。
     */
    public static final String WORKORDER_NO_LOCK_KEY = "lock:workorder:no";

    /**
     * 缓存重建锁前缀,完整 key:{@code lock:cache:{cacheKey}}。
     * <p>
     * 例:详情缓存 key 为 {@code workorder:detail:1},其重建锁即
     * {@code lock:cache:workorder:detail:1}。锁与缓存同 Key 段命名,
     * 排查时可从缓存键直接推出锁键。
     */
    public static final String CACHE_REBUILD_LOCK_PREFIX = "lock:cache:";

    /**
     * 资源申请互斥锁前缀,完整 key:{@code lock:resource:apply:{deptId}:{type}:{name}}。
     * <p>
     * 粒度是「部门 + 资源类别 + 资源名称」:同部门对同一种资源的并发申请互相排斥,
     * 不同部门 / 不同资源不互斥(把它们串起来是过度加锁,会无谓降低吞吐)。
     * 一单多资源时由 {@code Redisson.getMultiLock} 组合多把锁,并按 (type,name) 排序防死锁。
     */
    public static final String RESOURCE_APPLY_LOCK_PREFIX = "lock:resource:apply:";
}
