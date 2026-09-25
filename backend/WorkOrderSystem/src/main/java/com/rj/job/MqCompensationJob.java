package com.rj.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rj.config.MqNotifyProperties;
import com.rj.mapper.MqMessageReliabilityMapper;
import com.rj.model.pojo.MqMessageReliability;
import com.rj.mq.NotifyMessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知投递补偿任务:三段式可靠性的<strong>第三段</strong>,覆盖前两段兜不住的窗口。
 * <p>
 * 每轮做两件事:
 * <ol>
 *   <li><strong>回收孤儿</strong>:{@code status=1} 表示「已认领、正在发送」。进程若在认领之后、
 *       拿到 confirm 之前崩溃,这一行会永远停在 1——既不会被扫描(第 2 步只扫 0),
 *       也永远不会成功。<strong>一个只写不清理的状态机一定会积压。</strong>
 *       把它复位为 0 交给下轮重投。理论上可能误伤「发送耗时超过一个扫描周期」的行,
 *       代价是重复投递一次——而幂等吸收重复正是为此存在的。</li>
 *   <li><strong>扫描待发 + 认领 + 重投</strong>:候选条件 {@code status=0 且 next_retry_time 已到
 *       且 retry_count < maxRetry},走 {@code idx_status_retry(status, next_retry_time)}
 *       等值+范围,{@code LIMIT} 到量即停,不扫描历史数据。稳态下这里扫不到任何东西,
 *       零写入——这是<em>降级路径</em>,不是常态路径。</li>
 * </ol>
 * 具体发送与终态回写(含重试耗尽收口 {@code status=3})全部委托给
 * {@link NotifyMessagePublisher},保证「提交后发送」与「补偿重投」两条路径行为不可能分叉。
 * <p>
 * 受 {@code mq.notify.enabled} 门控:关闭时本 bean 不存在(见设计文档 6.5)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mq.notify", name = "enabled", havingValue = "true")
public class MqCompensationJob {

    private final MqMessageReliabilityMapper mqMessageReliabilityMapper;
    private final NotifyMessagePublisher notifyMessagePublisher;
    private final MqNotifyProperties properties;

    /**
     * 定时触发:上一轮结束后固定延迟再触发下一轮,避免发送较慢时任务堆积。
     * <p>
     * 单轮失败不向上抛:调度器不会因为一次数据库抖动就停掉整个任务。
     */
    @Scheduled(fixedDelayString = "${mq.notify.compensation.scan-delay-ms:30000}")
    public void compensate() {
        MqNotifyProperties.Compensation config = properties.getCompensation();
        if (!config.isEnabled()) {
            return;
        }
        try {
            int reaped = reapOrphanClaims();
            if (reaped > 0) {
                log.warn("回收「已认领未确认」孤儿消息 {} 条, 已交回待发送队列", reaped);
            }

            List<MqMessageReliability> candidates = selectPending(config.getBatchSize(), config.getMaxRetry());
            if (candidates.isEmpty()) {
                return;
            }
            List<MqMessageReliability> claimed = notifyMessagePublisher.claim(candidates);
            if (!claimed.isEmpty()) {
                log.info("通知补偿重投: 候选 {} 条, 认领 {} 条", candidates.size(), claimed.size());
                notifyMessagePublisher.publishBatch(claimed);
            }
        } catch (Exception e) {
            // 单次失败不应中断后续调度,记录后等下一轮重试
            log.error("通知补偿任务执行失败", e);
        }
    }

    /**
     * 回收「认领后进程崩溃」的孤儿:{@code status=1 → 0}。
     * <p>
     * 条件用数据库的 {@code NOW()} 而非应用时钟,避免应用与数据库时钟漂移时
     * 复位的范围与扫描的范围不一致。
     *
     * @return 复位的行数
     */
    private int reapOrphanClaims() {
        LambdaUpdateWrapper<MqMessageReliability> wrapper = Wrappers.<MqMessageReliability>lambdaUpdate()
                .eq(MqMessageReliability::getStatus, NotifyMessagePublisher.STATUS_CLAIMED)
                .apply("next_retry_time <= NOW()")
                .set(MqMessageReliability::getStatus, NotifyMessagePublisher.STATUS_PENDING);
        return mqMessageReliabilityMapper.update(null, wrapper);
    }

    /**
     * 捞取待发送消息。{@code retry_count < maxRetry} 是双重保险:重试耗尽的记录
     * 在发送失败时已被收口为 {@code status=3},这里再挡一道,任何漏网的都不会被无限重投。
     *
     * @param batchSize 单轮上限
     * @param maxRetry  最大重试次数
     */
    private List<MqMessageReliability> selectPending(int batchSize, int maxRetry) {
        LambdaQueryWrapper<MqMessageReliability> wrapper = Wrappers.<MqMessageReliability>lambdaQuery()
                .eq(MqMessageReliability::getStatus, NotifyMessagePublisher.STATUS_PENDING)
                .apply("next_retry_time <= NOW()")
                .lt(MqMessageReliability::getRetryCount, maxRetry)
                .orderByAsc(MqMessageReliability::getNextRetryTime)
                // 上限来自配置(非外部输入),仅用于满足「LIMIT 到量即停」的索引扫描形态
                .last("LIMIT " + batchSize);
        return mqMessageReliabilityMapper.selectList(wrapper);
    }
}
