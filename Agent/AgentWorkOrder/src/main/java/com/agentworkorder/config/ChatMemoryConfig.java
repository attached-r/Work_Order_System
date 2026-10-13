package com.agentworkorder.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆配置（M1 从简，只用内存版）。
 *
 * <p>刻意把 {@link ChatMemory} 暴露成 Bean：M1 用内存版 {@link MessageWindowChatMemory}「跑通」多轮对话即可；
 * 将来（M5）换成 Redis 版时，接口一致，只需换这里的实现，业务代码（{@code SpringAiLlmClient}）不动。
 *
 * <p>注意内存版的固有局限：<b>重启即丢、多实例不共享</b>——M1 可接受。
 */
@Configuration
public class ChatMemoryConfig {

    /** 每个会话保留的最大消息条数（约等于 N 轮问答），防止上下文无限膨胀。 */
    private static final int MAX_MESSAGES = 20;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MAX_MESSAGES)
                .build();
    }
}
