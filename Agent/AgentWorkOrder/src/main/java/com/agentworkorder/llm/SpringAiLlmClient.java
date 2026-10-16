package com.agentworkorder.llm;

import com.agentworkorder.config.LlmProperties;
import com.agentworkorder.perm.PermService;
import com.agentworkorder.perm.ToolPermissionRegistry;
import com.agentworkorder.perm.UserPerms;
import com.agentworkorder.tool.ToolContextKeys;
import com.agentworkorder.tool.WorkOrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 默认适配器：走真实大模型（Spring AI 的 {@link ChatClient}）。
 *
 * <p>只在 {@code agent.llm.provider=openai} 时启用。装配要点：
 * <ol>
 *   <li>{@code defaultSystem(...)} 注入系统提示词；</li>
 *   <li>{@code defaultAdvisors(MessageChatMemoryAdvisor)} 挂上会话记忆，按 {@code sessionId} 分会话；</li>
 *   <li><b>工具不再在构建期注册</b>——M1 用 {@code defaultTools(...)} 一次性把全部工具交给模型，
 *       M2 要按用户权限<b>每请求收敛可见工具</b>，故改为在 {@link #stream} 里用
 *       {@code .toolCallbacks(visible)} 动态注册。构建期只把全部工具方法编成 {@link ToolCallback} 缓存起来。</li>
 * </ol>
 *
 * <p>最关键的一点在 {@link #stream}：用户 JWT 通过 {@code toolContext} <b>显式</b>塞进工具上下文，
 * 而不是用 ThreadLocal 隐式传递——因为流式 + 工具调用的执行线程不保证是接收请求的那条线程。
 * M2 起该上下文还额外携带 session/trace/userId/provider，供审计埋点读取。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "openai")
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;
    private final String provider;

    /** 构建期一次性编译出的全部工具回调（未过滤）；每请求再按权限收敛子集。 */
    private final ToolCallback[] allToolCallbacks;

    private final PermService permService;
    private final ToolPermissionRegistry permissionRegistry;

    public SpringAiLlmClient(ChatClient.Builder builder,
                             WorkOrderTools workOrderTools,
                             LlmProperties llmProperties,
                             ChatMemory chatMemory,
                             PermService permService,
                             ToolPermissionRegistry permissionRegistry) {
        this.provider = llmProperties.getProvider();
        this.permService = permService;
        this.permissionRegistry = permissionRegistry;
        this.chatClient = builder
                .defaultSystem(llmProperties.getSystemPrompt())
                // 注意：这里刻意不再 defaultTools(...)——工具改到每请求按权限注册
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        // 把 @Tool 方法编成 ToolCallback[]（对象级：两个方法都在同一个 @Component 内）
        this.allToolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(workOrderTools)
                .build()
                .getToolCallbacks();
    }

    @Override
    public Flux<String> stream(String sessionId, String userMessage, String authToken) {
        // 一轮对话一个 traceId，供本轮所有工具调用的审计流水聚合
        String traceId = UUID.randomUUID().toString();

        UserPerms perms = permService.load(authToken, traceId);
        List<ToolCallback> visible = visibleToolCallbacks(perms);
        log.info("会话 {} 处理请求：trace={} provider={} user={} 可见工具={}",
                sessionId, traceId, provider, perms.username(), visible.size());

        return chatClient.prompt()
                .user(userMessage)
                // 本次会话的记忆键；MessageChatMemoryAdvisor 据此读写对应会话的消息窗口
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                // 只把用户有权使用的工具交给模型（无权限的工具模型根本看不到，fail-closed）
                .toolCallbacks(visible)
                // JWT + session/trace/userId/provider 显式放进工具上下文，供 @Tool 透传与审计读取
                .toolContext(ToolContextKeys.build(authToken, sessionId, traceId, perms, provider))
                .stream()
                .content();
    }

    /** 按用户权限过滤工具：只保留「所需权限码在用户清单里」的 {@link ToolCallback}。 */
    private List<ToolCallback> visibleToolCallbacks(UserPerms perms) {
        return Arrays.stream(allToolCallbacks)
                .filter(callback -> permissionRegistry.visibleTo(callback.getToolDefinition().name(), perms))
                .toList();
    }
}
