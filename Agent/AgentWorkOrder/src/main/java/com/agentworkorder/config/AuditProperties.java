package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计相关配置，绑定 {@code agent.audit.*}。
 *
 * <p>M2 只有「开关 + 摘要长度」两个旋钮；M5 增加保留期清理：
 * <ul>
 *   <li>{@link #enabled} —— 审计写入总开关，关掉时埋点直接短路；</li>
 *   <li>{@link #excerptMaxLength} —— 返回体摘要截断长度；</li>
 *   <li>{@code retention*} —— 按天分批清理，避免只增不减的表无限膨胀。</li>
 * </ul>
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "agent.audit")
public class AuditProperties {

    /** 是否启用审计写入；默认开启。 */
    private boolean enabled = true;

    /** 返回体摘要 {@code result_excerpt} 的最大长度（字符），需 ≤ 表列宽 512。 */
    private int excerptMaxLength = 512;

    /** 是否启用审计保留期清理；默认开启。 */
    private boolean retentionEnabled = true;

    /** 保留天数；<=0 表示不清理。 */
    private int retentionDays = 90;

    /** 清理任务 cron（Spring 6 段，含秒）；默认每天 03:30。 */
    private String retentionCron = "0 30 3 * * *";

    /** 每批删除行数，避免长事务 / 大 binlog。 */
    private int retentionBatchSize = 1000;

    /** 单次任务最多删多少批，避免一次跑太久。 */
    private int retentionMaxBatches = 20;
}
