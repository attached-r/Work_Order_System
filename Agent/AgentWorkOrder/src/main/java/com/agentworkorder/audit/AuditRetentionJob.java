package com.agentworkorder.audit;

import com.agentworkorder.audit.mapper.AgentToolCallLogMapper;
import com.agentworkorder.config.AuditProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计保留期清理（M5 §9.3）。
 *
 * <p>审计表是 append-only，长期会膨胀。这里每天按保留期<b>分批</b>删除：
 * <ul>
 *   <li>一次只删 {@code retentionBatchSize} 行，避免长事务/大 binlog；</li>
 *   <li>最多删 {@code retentionMaxBatches} 批，避免一次跑太久；</li>
 *   <li>删除失败只告警，不能让调度线程死掉。</li>
 * </ul>
 *
 * <p>前提：{@code create_time} 上要有单列索引，否则 {@code WHERE create_time < ?} 会全表扫描。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.audit.retention-enabled", havingValue = "true", matchIfMissing = true)
public class AuditRetentionJob {

    private final AgentToolCallLogMapper mapper;
    private final AuditProperties properties;

    public AuditRetentionJob(AgentToolCallLogMapper mapper, AuditProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Scheduled(cron = "${agent.audit.retention-cron:0 30 3 * * *}")
    public void purgeExpired() {
        int days = properties.getRetentionDays();
        if (days <= 0) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        int batchSize = Math.max(1, properties.getRetentionBatchSize());
        int maxBatches = Math.max(1, properties.getRetentionMaxBatches());

        int total = 0;
        try {
            for (int i = 0; i < maxBatches; i++) {
                int deleted = mapper.deleteOlderThan(cutoff, batchSize);
                total += deleted;
                // 不足一批说明已经删完，提前结束
                if (deleted < batchSize) {
                    break;
                }
            }
        } catch (Exception e) {
            // 清理失败只告警，不影响应用；下次调度会继续
            log.warn("审计清理失败：cutoff={} 已删={} 行 err={}", cutoff, total, e.toString());
            return;
        }

        if (total > 0) {
            log.info("审计清理完成：cutoff={} 删除 {} 行", cutoff, total);
        }
    }
}
