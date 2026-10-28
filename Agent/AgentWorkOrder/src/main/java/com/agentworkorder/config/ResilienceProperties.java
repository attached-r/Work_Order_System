package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 熔断配置（M5 §7.3），绑定 {@code agent.resilience.*}。
 *
 * <p>刻意只做「连续失败」的轻量熔断，不引入 Sentinel / Resilience4j：
 * 当前只有项目一、LLM、RAG 三个下游，用 100 行左右的状态机就够，
 * 而且每个字段都能在面试里讲清楚。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.resilience")
public class ResilienceProperties {

    /** 总开关：false 时只保留超时/fallback，不做熔断（排障用） */
    private boolean enabled = true;

    /** 连续失败多少次后打开 */
    private int failureThreshold = 5;

    /** 打开后多少秒允许一次半开试探 */
    private int openSeconds = 30;

    /** 半开状态最多放行几个试探请求 */
    private int halfOpenMaxCalls = 1;
}
