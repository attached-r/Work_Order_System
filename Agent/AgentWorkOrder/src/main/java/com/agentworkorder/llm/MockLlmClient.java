package com.agentworkorder.llm;

import com.agentworkorder.perm.PermService;
import com.agentworkorder.perm.ToolPermissionRegistry;
import com.agentworkorder.perm.UserPerms;
import com.agentworkorder.tool.ToolContextKeys;
import com.agentworkorder.tool.WorkOrderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;

/**
 * Mock 适配器：{@code agent.llm.provider=mock}（默认）时启用，<b>不调真模型</b>，无需 API key。
 *
 * <p>但它并非单纯回显——它会真的去调用 {@link WorkOrderTools}（进而经 {@code WorkOrderToolClient}
 * 打到项目一 {@code /tool/**}）。这样做的价值是：<b>在没有 API key 的情况下，也能把
 * 「工具 → RestClient → 项目一 → 回喂 → SSE」整条链路跑通并验证</b>，正是设计文档 §12 验证步骤 2 依赖的能力。
 * 鉴权（反向验证 JWT 透传，§12 步骤 4）同样靠它：不带 token 时项目一会回 401 错误体，这里原样吐出。
 *
 * <p>M2 起 mock 与 openai 两条路径<b>行为一致</b>：都先取用户 {@code perms}、按「工具→权限码」注册表
 * 收敛可见工具。无权限时 mock 也<b>不调用</b>工具，直接回答「未登录或无权查询工单」（fail-closed），
 * 从而在没有真实模型、无法观察 tool-calling 的情况下也能验证权限闸门。
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    /** mock 的“提供方”标识，写入审计流水。 */
    private static final String PROVIDER = "mock";

    /** 每个字符之间的间隔，用来模拟流式输出的「逐字吐字」效果。 */
    private static final Duration CHUNK_DELAY = Duration.ofMillis(8);

    private final WorkOrderTools workOrderTools;
    private final PermService permService;
    private final ToolPermissionRegistry permissionRegistry;

    public MockLlmClient(WorkOrderTools workOrderTools,
                         PermService permService,
                         ToolPermissionRegistry permissionRegistry) {
        this.workOrderTools = workOrderTools;
        this.permService = permService;
        this.permissionRegistry = permissionRegistry;
    }

    @Override
    public Flux<String> stream(String sessionId, String userMessage, String authToken) {
        String reply = buildReply(sessionId, userMessage, authToken);
        // 按字符拆开、加一点延迟，观察端到端确实是一段段 SSE 事件推过来的
        return Flux.fromArray(reply.split("")).delayElements(CHUNK_DELAY);
    }

    private String buildReply(String sessionId, String userMessage, String authToken) {
        String traceId = UUID.randomUUID().toString();
        UserPerms perms = permService.load(authToken, traceId);

        // 与 openai 路径同一套可见性判定：无 workorder:query → 工具不可见
        if (!permissionRegistry.visibleTo("listWorkOrders", perms)) {
            return "[mock] 收到问题：" + userMessage + "\n"
                    + "[mock] 未登录或当前用户无权查询工单（工具不可见，fail-closed）。";
        }

        String toolResult;
        try {
            // 用统一工厂构造上下文（auth/session/trace/userId/provider），与 SpringAiLlmClient 完全一致
            ToolContext context = new ToolContext(
                    ToolContextKeys.build(authToken, sessionId, traceId, perms, PROVIDER));
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
