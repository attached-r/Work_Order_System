package com.agentworkorder.config;

import com.agentworkorder.memory.RedisChatMemoryRepository;
import com.agentworkorder.redis.JsonCodec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 会话记忆装配（M5 改造）。
 *
 * <p>只替换 Repository，不动 MessageWindowChatMemory；这样「20 条窗口」的语义与 M1–M4 完全一致。
 * <p>agent.memory.store=memory 可一键回滚到 M4 行为（无 Redis 也能开发）。
 */
@Configuration
public class ChatMemoryConfig {
    // SpringBoot 启动扫描时 自动注入
    @Bean
    @ConditionalOnProperty(name = "agent.memory.store", havingValue = "redis")
    public ChatMemoryRepository redisChatMemoryRepository(StringRedisTemplate redis,
                                                          JsonCodec codec,
                                                          MemoryProperties properties) {
        return new RedisChatMemoryRepository(
                redis,
                codec,
                properties.getKeyPrefix(),
                Duration.ofDays(Math.max(1, properties.getTtlDays())));
    }

    @Bean
    @ConditionalOnProperty(name = "agent.memory.store", havingValue = "memory", matchIfMissing = true)
    public ChatMemoryRepository inMemoryChatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, MemoryProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(Math.max(1, properties.getMaxMessages()))
                .build();
    }
}
