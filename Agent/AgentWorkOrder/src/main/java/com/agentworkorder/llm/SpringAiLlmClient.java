package com.agentworkorder.llm;

import com.agentworkorder.config.LlmProperties;
import com.agentworkorder.tool.WorkOrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 默认适配器：走真实大模型（Spring AI 的 {@link ChatClient}）。
 *
 * <p>只在 {@code agent.llm.provider=openai} 时启用。装配要点：
 * <ol>
 *   <li>{@code defaultSystem(...)} 注入系统提示词；</li>
 *   <li>{@code defaultTools(tools)} 把 {@link WorkOrderTools} 里带 {@code @Tool} 的方法注册成模型可调用的工具；</li>
 *   <li>{@code defaultAdvisors(MessageChatMemoryAdvisor)} 挂上会话记忆，按 {@code sessionId} 分会话。</li>
 * </ol>
 *
 * <p>最关键的一点在 {@link #stream}：用户 JWT 通过 {@code toolContext} <b>显式</b>塞进工具上下文，
 * 而不是用 ThreadLocal 隐式传递——因为流式 + 工具调用的执行线程不保证是接收请求的那条线程
 * （详见设计文档 §6.2 / §6.3）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "openai")
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;

    public SpringAiLlmClient(ChatClient.Builder builder,
                             WorkOrderTools workOrderTools,
                             LlmProperties llmProperties,
                             ChatMemory chatMemory) {
        this.chatClient = builder
                .defaultSystem(llmProperties.getSystemPrompt())
                .defaultTools(workOrderTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Override
    public Flux<String> stream(String sessionId, String userMessage, String authToken) {
        log.info("ok");
        return chatClient.prompt()
                .user(userMessage)
                // 本次会话的记忆键；MessageChatMemoryAdvisor 据此读写对应会话的消息窗口
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                // 把 JWT 放进工具上下文：模型决定调工具时，@Tool 方法可从 ToolContext 里取出来透传给项目一
                .toolContext(Map.of(WorkOrderTools.AUTH_HEADER_KEY, authToken == null ? "" : authToken))
                .stream()
                .content();
    }
}
