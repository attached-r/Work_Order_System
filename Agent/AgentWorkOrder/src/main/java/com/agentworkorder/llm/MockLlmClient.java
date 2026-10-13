package com.agentworkorder.llm;

import com.agentworkorder.tool.WorkOrderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * Mock 适配器：{@code agent.llm.provider=mock}（默认）时启用，<b>不调真模型</b>，无需 API key。
 *
 * <p>但它并非单纯回显——它会真的去调用 {@link WorkOrderTools}（进而经 {@code WorkOrderToolClient}
 * 打到项目一 {@code /tool/**}）。这样做的价值是：<b>在没有 API key 的情况下，也能把
 * 「工具 → RestClient → 项目一 → 回喂 → SSE」整条链路跑通并验证</b>，正是设计文档 §12 验证步骤 2 依赖的能力。
 * 鉴权（反向验证 JWT 透传，§12 步骤 4）同样靠它：不带 token 时项目一会回 401 错误体，这里原样吐出。
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    /** 每个字符之间的间隔，用来模拟流式输出的「逐字吐字」效果。 */
    private static final Duration CHUNK_DELAY = Duration.ofMillis(8);

    private final WorkOrderTools workOrderTools;

    public MockLlmClient(WorkOrderTools workOrderTools) {
        this.workOrderTools = workOrderTools;
    }

    @Override
    public Flux<String> stream(String sessionId, String userMessage, String authToken) {
        String reply = buildReply(userMessage, authToken);
        // 按字符拆开、加一点延迟，观察端到端确实是一段段 SSE 事件推过来的
        return Flux.fromArray(reply.split("")).delayElements(CHUNK_DELAY);
    }

    private String buildReply(String userMessage, String authToken) {
        String toolResult;
        try {
            // 直接把「当前会话的 token」放进 ToolContext 调工具，模拟真实模型触发工具调用的那一刻
            ToolContext context = new ToolContext(
                    Map.of(WorkOrderTools.AUTH_HEADER_KEY, authToken == null ? "" : authToken));
            toolResult = workOrderTools.listWorkOrders(null, null, null, context);
        } catch (Exception e) {
            // 项目一没起 / 网络不通时，把原因作为回答吐出，避免整条 SSE 直接中断
            log.warn("mock 调用工具失败：{}", e.getMessage());
            toolResult = "工具调用失败：" + e.getMessage();
        }

        return "[mock] 收到问题：" + userMessage + "\n"
                + "[mock] 已调用工具 listWorkOrders，项目一返回：\n"
                + toolResult + "\n"
                + "[mock] （配置 agent.llm.provider=openai 并填入 API key 后，将由真实大模型组织自然语言回答）";
    }
}
