package com.rj.lock;

import com.rj.common.ResultCode;
import com.rj.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 分布式锁封装(模块四)。
 * <p>
 * 只做一件事:把「{@code tryLock} → 执行临界区 → {@code unlock}」的固定套路收敛到一处,
 * 统一「显式租约、持有者校验、异常语义」。业务代码不直接碰 {@link RedissonClient}。
 * <p>
 * 三条纪律(与设计文档 6.4 一致):
 * <ul>
 *   <li><b>显式租约</b>:一律用 {@code tryLock(wait, lease)} 两参形式,不用无参 {@code lock()}
 *       并关闭看门狗自动续约——「锁最多持有多久」应是一个可读常量,而不是不透明的后台续约。</li>
 *   <li><b>持有者才能释放</b>:释放前先 {@code isHeldByCurrentThread()},防止「未持有却解锁」。</li>
 *   <li><b>拿不到锁不降级</b>:写路径拿不到锁直接抛 409,<b>绝不</b>退化为「无锁执行」。</li>
 * </ul>
 * 唯一例外是缓存重建(见 {@code CacheClient}):它拿不到锁时退化为「直接回源 DB」,
 * 因为那是读路径优化,失败不该影响可用性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockHelper {

    private final RedissonClient redissonClient;

    /**
     * 尝试获取锁(不阻塞超过 {@code waitSeconds})。
     * <p>
     * 返回 {@code false} 表示在 wait 时间内没抢到锁;而 Redis 连接异常会原样抛出
     * (由 {@code GlobalExceptionHandler} 兜成 500)——<b>不吞异常</b>,避免把「互斥失效」
     * 静默降级成「无互斥执行」。
     *
     * @param lockKey      锁名,见 {@link com.rj.common.LockConstants}
     * @param waitSeconds  最长等待秒数;0 表示尝试一次立即返回
     * @param leaseSeconds 锁租约秒数,到期自动释放
     * @return 是否成功获取
     */
    public boolean tryLock(String lockKey, long waitSeconds, long leaseSeconds) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "获取分布式锁被中断");
        }
    }

    /**
     * 释放锁。只有当前线程持有该锁时才真正解锁。
     * <p>
     * 释放动作本身吞异常仅记 warn:即使释放失败,租约到期也会自动解锁,
     * 让「解锁失败」不至于反向打断已完成的业务。
     */
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // isHeldByCurrentThread 作用是判断是否当前线程持有锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放分布式锁异常(交由租约到期自愈): lockKey={}", lockKey, e);
        }
    }

    /**
     * 加单锁执行临界区并返回结果。拿不到锁抛 409。
     *
     * @param lockKey      锁名
     * @param waitSeconds  最长等待秒数
     * @param leaseSeconds 锁租约秒数
     * @param action       临界区逻辑
     * @return 临界区返回值
     * @throws BusinessException 未获取到锁(409)
     */
    public <T> T executeWithLock(String lockKey, long waitSeconds, long leaseSeconds, Supplier<T> action) {
        if (!tryLock(lockKey, waitSeconds, leaseSeconds)) {
            throw new BusinessException(ResultCode.CONFLICT, "操作过于频繁,请稍后重试");
        }
        try {
            return action.get();
        } finally {
            unlock(lockKey);
        }
    }

    /**
     * 加多把锁(MultiLock)执行临界区并返回结果。拿不到锁抛 409。
     * <p>
     * <b>排序是防死锁的关键</b>:两个请求若涉及资源集合 {A,B} 与 {B,A},不排序会互相等锁而死锁。
     * 这里对锁名<b>去重 + 字典序排序</b>后再依次加锁,保证所有调用方以同一顺序获取。
     *
     * @param lockKeys     锁名集合(内部会去重、排序)
     * @param waitSeconds  最长等待秒数
     * @param leaseSeconds 锁租约秒数
     * @param action       临界区逻辑
     * @return 临界区返回值
     * @throws BusinessException 未获取到锁(409)
     */
    public <T> T executeWithMultiLock(Collection<String> lockKeys, long waitSeconds, long leaseSeconds,
                                        Supplier<T> action) {
        // 1. 去重 + 字典排序(防死锁)
        List<String> sortedKeys = lockKeys.stream().distinct().sorted().toList();
        // 2.把lockKey转换为RLock锁对象
        RLock[] locks = sortedKeys.stream().map(redissonClient::getLock).toArray(RLock[]::new);
        // 3. 组装成MultiLock
        RLock multiLock = redissonClient.getMultiLock(locks);

        // 尝试获取全部锁
        try {
            if (!multiLock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS)) {
                // 超时 没有拿到全部锁 抛出409
                throw new BusinessException(ResultCode.CONFLICT, "操作过于频繁,请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR, "获取分布式锁被中断");
        }
        // 获取全部锁 成功
        try {
            return action.get();// 执行临界区业务逻辑
        } finally {
            try {
                if (multiLock.isHeldByCurrentThread()) {
                    multiLock.unlock();
                }
            } catch (Exception e) {
                log.warn("释放 MultiLock 异常(交由租约到期自愈): lockKeys={}", sortedKeys, e);
            }
        }
    }
}
