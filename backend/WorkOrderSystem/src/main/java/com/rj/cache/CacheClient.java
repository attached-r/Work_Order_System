package com.rj.cache;

import com.rj.common.LockConstants;
import com.rj.config.RedisLockProperties;
import com.rj.lock.RedisLockHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 缓存统一入口(模块四)。
 * <p>
 * 「读穿 + 写失效」的快取套路都收敛在这里,业务层只调本类,不直接碰 {@link RedisTemplate}。
 * 提供两类读法:
 * <ul>
 *   <li>{@link #getOrLoad}:普通 TTL 缓存(权限树、角色权限)。未命中时<b>互斥重建</b>防击穿;</li>
 *   <li>{@link #getLogical}:逻辑过期缓存(工单详情)。在互斥重建之上叠加
 *       <b>逻辑过期 + 异步重建 + 空值哨兵</b>,超热键读请求永不阻塞。</li>
 * </ul>
 * <p>
 * <b>为什么不用 {@code @Cacheable}?</b> 注解表达不出本模块要的三件事:逻辑过期(值内带 expireAt)、
 * 互斥重建(未命中加锁 + 二次检查)、空值缓存的独立短 TTL。在只有 3 个缓存点的规模下,
 * 显式实现更诚实可控。
 * <p>
 * <b>异常边界</b>:缓存读写与重建锁获取的异常都被吞掉并记 warn——缓存是「可选优化」,
 * 抖动时应退化为「直接回源 DB」,绝不能反向搞挂业务读。这与「写路径拿不到锁不降级」(抛 409)
 * 是刻意的分野:前者是读优化,后者是写互斥。
 * <p>
 * 序列化沿用模块一定型的 {@code RedisTemplate}(Jackson3 + default typing 写 {@code @class}),
 * 不引入第二套序列化器;Redisson 只出锁(StringCodec),两套序列化永不相遇。
 */
@Slf4j
@Component
public class CacheClient {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisLockHelper redisLockHelper;
    private final RedisLockProperties lockProperties;
    private final Executor cacheRebuildExecutor;

    public CacheClient(RedisTemplate<String, Object> redisTemplate,
                       StringRedisTemplate stringRedisTemplate,
                       RedisLockHelper redisLockHelper,
                       RedisLockProperties lockProperties,
                       @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisLockHelper = redisLockHelper;
        this.lockProperties = lockProperties;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
    }

    // ------------------------------------------------------------------
    // 读穿:普通 TTL 缓存 + 互斥重建
    // ------------------------------------------------------------------

    /**
     * 读穿(TTL 缓存 + 互斥重建)。
     * <p>
     * 未命中 → 尝试加重建锁 → 抢到则二次检查后回源并回填;抢不到则<b>不阻塞、直接回源</b>。
     * 「抢不到锁退化为直接回源」是用「多打几次 DB」换「不阻塞」——热点 Key 失效瞬间,
     * 若让所有请求都排队等锁,正好会把线程池堆满,那正是击穿想避免的后果。
     *
     * @param key        缓存 key
     * @param ttlSeconds 物理 TTL 秒数
     * @param loader     回源加载器;返回 null 表示「不缓存」(下次仍会回源)
     * @param <T>        值类型
     * @return 缓存值或回源结果
     */
    public <T> T getOrLoad(String key, long ttlSeconds, Supplier<T> loader) {
        // 1.检查缓存
        Object cached = getValue(key);
        if (cached != null) {
            return cast(cached);
        }
        // 2.缓存未命中 进入回源逻辑 准备抢锁
        String lockKey = LockConstants.CACHE_REBUILD_LOCK_PREFIX + key;
        // 尝试获取分布式锁 失败 -> 调用loader回源
        if (!tryLockQuietly(lockKey)) {
            return loader.get();
        }
        try {
            // 二次检查:拿锁期间可能已被其他线程重建好,避免重复回源
            Object again = getValue(key);
            if (again != null) {
                return cast(again);
            }
            T value = loader.get();
            if (value != null) {
                putValue(key, value, ttlSeconds);
            }
            return value;
        } finally {
            // 释放锁
            redisLockHelper.unlock(lockKey);
        }
    }

    // ------------------------------------------------------------------
    // 读穿:逻辑过期缓存 + 互斥重建 + 空值哨兵
    // ------------------------------------------------------------------

    /**
     * 读穿(逻辑过期缓存)。
     * <p>
     * 流程:
     * <ol>
     *   <li>命中空值哨兵 → 直接判定「不存在」(返回 null),不打 DB(防穿透);</li>
     *   <li>命中且逻辑未过期 → 直接返回;</li>
     *   <li>命中但逻辑已过期 → <b>立即返回旧值</b> + 提交异步重建(单飞,不阻塞读);</li>
     *   <li>未命中 → 同步互斥重建。</li>
     * </ol>
     * 回源返回 null 时写入短 TTL 的空值哨兵(写的是 {@code absentKey},与数据 key 分开)。
     *
     * @param key                数据缓存 key
     * @param absentKey          空值哨兵 key(可传 null 表示不启用穿透防护)
     * @param logicalTtlSeconds  逻辑过期秒数
     * @param physicalTtlSeconds 物理过期秒数(应大于逻辑过期)
     * @param absentTtlSeconds   空值哨兵物理过期秒数
     * @param loader             回源加载器;返回 null 表示「数据不存在」
     * @param <T>                值类型
     * @return 缓存值;数据不存在时返回 null
     */
    public <T> T getLogical(String key, String absentKey,
                            long logicalTtlSeconds, long physicalTtlSeconds, long absentTtlSeconds,
                            Supplier<T> loader) {
        // 1. 空值哨兵:说明该 id 此前回源判定为「不存在」
        if (absentKey != null && hasKey(absentKey)) {
            return null;
        }

        // 2. 读逻辑过期包装
        RedisData<?> wrapper = getRedisData(key);
        if (wrapper != null) {
            if (wrapper.getExpireAtEpochMilli() > System.currentTimeMillis()) {
                return cast(wrapper.getData());
            }
            // 逻辑已过期:先还给用户旧值,再异步重建,读请求不阻塞
            submitRebuild(key, absentKey, logicalTtlSeconds, physicalTtlSeconds, absentTtlSeconds, loader);
            return cast(wrapper.getData());
        }

        // 3. 未命中:同步互斥重建
        return rebuild(key, absentKey, logicalTtlSeconds, physicalTtlSeconds, absentTtlSeconds, loader);
    }

    /** 同步互斥重建:抢到锁则二次检查后回源回填;抢不到则不阻塞,直接回源。 */
    private <T> T rebuild(String key, String absentKey, long logicalTtl, long physicalTtl, long absentTtl,
                          Supplier<T> loader) {
        String lockKey = LockConstants.CACHE_REBUILD_LOCK_PREFIX + key;
        if (!tryLockQuietly(lockKey)) {
            return loader.get();
        }
        try {
            RedisData<?> again = getRedisData(key);
            if (again != null && again.getExpireAtEpochMilli() > System.currentTimeMillis()) {
                return cast(again.getData());
            }
            return loadAndFill(key, absentKey, logicalTtl, physicalTtl, absentTtl, loader);
        } finally {
            redisLockHelper.unlock(lockKey);
        }
    }

    /**
     * 提交异步重建任务。任务本身可能被线程池丢弃(队列满),那没关系——
     * 重建是可丢弃的优化,这次丢了下次读还会触发。
     */
    private <T> void submitRebuild(String key, String absentKey, long logicalTtl, long physicalTtl,
                                   long absentTtl, Supplier<T> loader) {
        try {
            cacheRebuildExecutor.execute(() -> {
                String lockKey = LockConstants.CACHE_REBUILD_LOCK_PREFIX + key;
                if (!tryLockQuietly(lockKey)) {
                    return;
                }
                try {
                    RedisData<?> again = getRedisData(key);
                    if (again != null && again.getExpireAtEpochMilli() > System.currentTimeMillis()) {
                        // 已被其他线程重建好,本次跳过,保证「逻辑过期瞬间只触发一次重建」
                        return;
                    }
                    loadAndFill(key, absentKey, logicalTtl, physicalTtl, absentTtl, loader);
                } catch (Exception e) {
                    log.warn("缓存异步重建失败(下次读将再次触发): key={}", key, e);
                } finally {
                    redisLockHelper.unlock(lockKey);
                }
            });
        } catch (Exception e) {
            log.warn("提交缓存异步重建任务失败(已丢弃): key={}", key, e);
        }
    }

    /** 回源 + 回填:加载到值则写逻辑过期包装;加载为空则写空值哨兵。 */
    private <T> T loadAndFill(String key, String absentKey, long logicalTtl, long physicalTtl,
                              long absentTtl, Supplier<T> loader) {
        T value = loader.get();
        if (value == null) {
            if (absentKey != null) {
                setString(absentKey, "1", absentTtl);
            }
            return null;
        }
        RedisData<T> data = new RedisData<>();
        data.setData(value);
        data.setExpireAtEpochMilli(System.currentTimeMillis() + logicalTtl * 1000L);
        putValue(key, data, physicalTtl);
        return value;
    }

    // ------------------------------------------------------------------
    // 写失效
    // ------------------------------------------------------------------

    /**
     * 立即删除缓存(吞异常记 warn)。
     * <p>
     * 与模块三 {@code evictUnreadCache} 同款处理:DEL 失败只记 warn,由 TTL 自愈,
     * <b>不让缓存抖动反向搞挂业务写</b>。
     */
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("缓存已失效: key={}", key);
        } catch (Exception e) {
            log.warn("缓存失效失败(由 TTL 兜底自愈): key={}", key, e);
        }
    }

    /**
     * 事务提交后删除缓存(推荐在写方法里调用)。
     * <p>
     * 「先改库、再删缓存」的正确性依赖「DEL 发生在事务提交之后」:若在事务内 DEL 而事务随后回滚,
     * 缓存已被删但数据没变,会把「无谓的重建」放大。通过 {@link TransactionSynchronizationManager}
     * 把 DEL 挂到提交后执行;无事务上下文时立即删除(与模块三投递侧的 {@code fallbackExecution} 同理)。
     */
    public void evictAfterCommit(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(key);
                }
            });
        } else {
            evict(key);
        }
    }

    // ------------------------------------------------------------------
    // 底层读写(全部吞异常,失败时按「未命中 / 忽略」处理)
    // ------------------------------------------------------------------

    private Object getValue(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读取缓存失败,按未命中处理: key={}", key, e);
            return null;
        }
    }

    private RedisData<?> getRedisData(String key) {
        Object value = getValue(key);
        return value instanceof RedisData<?> data ? data : null;
    }

    private void putValue(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("写入缓存失败(忽略): key={}", key, e);
        }
    }

    private boolean hasKey(String key) {
        try {
            // 避免空指针问题 且查询值为Boolean包装类 不能直接 == true
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("读取空值哨兵失败,按未命中处理: key={}", key, e);
            return false;
        }
    }

    private void setString(String key, String value, long ttlSeconds) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("写入空值哨兵失败(忽略): key={}", key, e);
        }
    }

    /** 抢重建锁:任何异常都退化为「没抢到」,由调用方走「直接回源」分支。 */
    private boolean tryLockQuietly(String lockKey) {
        try {
            return redisLockHelper.tryLock(lockKey,
                    lockProperties.getCacheRebuildWaitSeconds(), // 等待时间 1s
                    lockProperties.getCacheRebuildLeaseSeconds()); // 租约时间 5s
        } catch (Exception e) {
            log.warn("获取缓存重建锁失败,退化为直接回源: lockKey={}", lockKey, e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T cast(Object value) {
        return (T) value;
    }
}
