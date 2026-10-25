package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    /** memory=进程内（回滚用）；redis=Redis（M5 默认） */
    private String store = "memory";

    /** 每个会话保留的消息条数（约等于 N 轮） */
    private int maxMessages = 20;

    /** 会话记忆 TTL（天）；每次保存刷新 */
    private int ttlDays = 7;

    /** Redis key 前缀；多环境共用 Redis 时改成 agent:dev: 等 */
    private String keyPrefix = "agent:chat:memory:";
}
