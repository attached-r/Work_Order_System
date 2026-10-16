package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计相关配置，绑定 {@code agent.audit.*}。
 *
 * <p>只有两个旋钮，都是为了「审计绝不能拖垮主流程」这条硬性要求：
 * <ul>
 *   <li>{@link #enabled} —— 总开关。关掉时埋点直接短路（不拼 JSON、不写库），供压测时剥离审计开销；</li>
 *   <li>{@link #excerptMaxLength} —— 返回体摘要的截断长度。返回体可能很大且含业务明细，审计要的是
 *       「调过、大概返回了啥」，而不是数据副本，故截断后再存，同时也能避免 {@code Data too long}。</li>
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
}
