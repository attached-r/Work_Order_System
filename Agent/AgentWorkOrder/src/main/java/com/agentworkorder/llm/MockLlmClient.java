package com.agentworkorder.llm;

import com.agentworkorder.knowledge.KnowledgeTools;
import com.agentworkorder.perm.PermService;
import com.agentworkorder.perm.ToolPermissionRegistry;
import com.agentworkorder.perm.UserPerms;
import com.agentworkorder.tool.ToolContextKeys;
import com.agentworkorder.tool.WorkOrderTools;
import com.agentworkorder.tool.WorkOrderWriteTools;
import com.agentworkorder.write.WriteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 适配器：{@code agent.llm.provider=mock}（默认）时启用，<b>不调真模型</b>。
 *
 * <p>M4 增加第三类路由：<b>写意图</b>。mock 会真的调用写工具，因此与真实模型路径共享
 * 同一套「预览 → 用户确认 → 带令牌提交」逻辑、限流与审计；它只在「如何从自然语言判断动作」
 * 上比真实模型粗糙。
 *
 * <p>两轮确认由本类按会话记住最近一次预览的令牌与参数；真实模型路径不需要这张表，
 * 模型会自己携带工具返回的令牌。
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    /** mock 的“提供方”标识，写入审计流水。 */
    private static final String PROVIDER = "mock";

    /** 每个字符之间的间隔，用来模拟流式输出的「逐字吐字」效果。 */
    private static final Duration CHUNK_DELAY = Duration.ofMillis(8);

    /** 「明确在问实时数据」的关键词：命中时优先走 listWorkOrders。 */
    private static final List<String> DATA_KEYWORDS =
            List.of("列表", "详情", "哪张", "待处理", "现在在谁", "有几张");

    /** 「规则/流程类」关键词：命中且不是在问数据时，走 searchKnowledge（M3）。 */
    private static final List<String> RULE_KEYWORDS =
            List.of("流程", "状态", "权限", "驳回", "超时", "怎么", "如何", "规则", "验收",
                    "派单", "撤回", "取消", "转派", "重新提交", "能不能", "可以吗", "含义", "几步");

    /** 工单 ID：消息里的第一个 1–12 位数字（按 M4 约定，解析不到就让用户补充，绝不猜） */
    private static final Pattern ORDER_ID = Pattern.compile("(?<!\\d)(\\d{1,12})(?!\\d)");

    /** 显式 handlerId / userId / 处理人ID / 用户ID */
    private static final Pattern HANDLER_ID =
            Pattern.compile("(?i)(?:handlerId|userId|处理人\\s*ID|用户\\s*ID)\\s*[=:：]?\\s*(\\d{1,10})");

    private final WorkOrderTools workOrderTools;
    private final ObjectProvider<KnowledgeTools> knowledgeToolsProvider;
    private final ObjectProvider<WorkOrderWriteTools> writeToolsProvider;
    private final PermService permService;
    private final ToolPermissionRegistry permissionRegistry;
    private final WriteProperties writeProperties;
    private final ObjectMapper objectMapper;

    /** mock 专用：会话 → 最近一次预览（仅用于演示两轮确认，真实模型路径不依赖） */
    private final ConcurrentHashMap<String, PendingPreview> pendingPreviews = new ConcurrentHashMap<>();

    public MockLlmClient(WorkOrderTools workOrderTools,
                         ObjectProvider<KnowledgeTools> knowledgeToolsProvider,
                         ObjectProvider<WorkOrderWriteTools> writeToolsProvider,
                         PermService permService,
                         ToolPermissionRegistry permissionRegistry,
                         WriteProperties writeProperties,
                         ObjectMapper objectMapper) {
        this.workOrderTools = workOrderTools;
        this.knowledgeToolsProvider = knowledgeToolsProvider;
        this.writeToolsProvider = writeToolsProvider;
        this.permService = permService;
        this.permissionRegistry = permissionRegistry;
        this.writeProperties = writeProperties;
        this.objectMapper = objectMapper;
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

        // 步骤 1：构造统一工具上下文（auth/session/trace/userId/provider），与 SpringAiLlmClient 完全一致
        ToolContext context = new ToolContext(
                ToolContextKeys.build(authToken, sessionId, traceId, perms, PROVIDER));

        // 步骤 2：写路径优先。写权限可能没有 workorder:query，不能先被读闸门挡住
        String writeReply = tryWriteReply(sessionId, userMessage, perms, context);
        if (writeReply != null) {
            return writeReply;
        }

        // 步骤 3：与 openai 路径同一套可见性判定：无 workorder:query → 工具不可见（fail-closed）
        if (!permissionRegistry.visibleTo("listWorkOrders", perms)) {
            return "[mock] 收到问题：" + userMessage + "\n"
                    + "[mock] 未登录或当前用户无权查询工单（工具不可见，fail-closed）。";
        }

        // 步骤 4：M3 路由——规则类问题走 searchKnowledge，数据类问题走 listWorkOrders。
        KnowledgeTools knowledgeTools = knowledgeToolsProvider.getIfAvailable();
        if (knowledgeTools != null && shouldUseKnowledge(userMessage)) {
            String knowledgeResult;
            try {
                knowledgeResult = knowledgeTools.searchKnowledge(userMessage, null, context);
            } catch (Exception e) {
                log.warn("mock 调用知识检索失败：{}", e.getMessage());
                knowledgeResult = "知识检索失败：" + e.getMessage();
            }
            return "[mock] 收到问题：" + userMessage + "\n"
                    + "[mock] 判定为规则类问题，已调用工具 searchKnowledge，知识库返回：\n"
                    + knowledgeResult + "\n"
                    + "[mock] （配置 agent.llm.provider=openai 并填入 API key 后，将由真实大模型组织自然语言回答）";
        }

        // 步骤 5：数据类问题——真正调用项目一 /tool/**，验证「工具 → RestClient → 项目一 → 回喂」链路
        String toolResult;
        try {
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

    // ------------------------------------------------------------------
    // M4：写意图路由
    // ------------------------------------------------------------------

    /**
     * 尝试按写路径回答。
     *
     * @return 非 null 表示已处理（预览、确认、候选处理人等）；null 表示不是写意图，交给只读路由
     */
    private String tryWriteReply(String sessionId, String userMessage, UserPerms perms, ToolContext context) {
        WorkOrderWriteTools writeTools = writeToolsProvider.getIfAvailable();
        if (writeTools == null) {
            // 写功能总开关关闭：退回 M3 的只读行为
            return null;
        }

        // 1) 确认执行：有本会话待确认预览才提交；提交前再查一次权限
        if (contains(userMessage, writeProperties.getMockExecuteKeyword())) {
            PendingPreview pending = pendingPreviews.remove(sessionId);
            if (pending == null) {
                return "[mock] 当前会话没有待确认的写操作预览，请先说明要执行的动作和工单 ID。";
            }
            if (!permissionRegistry.visibleTo(pending.toolName(), perms)) {
                return noPerm(pending.toolName());
            }
            String result = callWriteTool(writeTools, pending, context);
            return "[mock] 收到确认，已携带令牌调用工具 " + pending.toolName() + "（真实写入）：\n" + result;
        }

        // 2) 只说「确认」：不触发写入，避免闲聊里的「确认」误操作
        if (contains(userMessage, "确认")) {
            return "[mock] 为避免误触发，请完整回复「" + writeProperties.getMockExecuteKeyword() + "」再提交本次预览。";
        }

        // 3) 候选处理人（只读）
        if (containsAny(userMessage, "候选处理人", "派给谁", "谁可以处理", "处理人列表")) {
            if (!permissionRegistry.visibleTo("listAssignableHandlers", perms)) {
                return noPerm("listAssignableHandlers");
            }
            return "[mock] 已调用工具 listAssignableHandlers：\n"
                    + writeTools.listAssignableHandlers(null, context);
        }

        // 4) 写意图
        WriteIntent intent = detectIntent(userMessage);
        if (intent == null) {
            return null;
        }
        if (!permissionRegistry.visibleTo(intent.toolName(), perms)) {
            return noPerm(intent.toolName());
        }

        Long orderId = extractOrderId(userMessage);
        if (orderId == null) {
            return "[mock] 未从消息中解析到工单 ID，请补充工单 ID（1-12 位数字）后再试。";
        }

        // 5) 派单/转派需要 handlerId：显式 ID 优先，否则用「派给X」里的姓名查候选
        Long handlerId = null;
        if ("DISPATCH".equals(intent.action()) || "TRANSFER".equals(intent.action())) {
            handlerId = extractHandlerId(userMessage);
            if (handlerId == null) {
                String handlerName = extractHandlerName(userMessage);
                if (handlerName == null) {
                    return "[mock] 请说明要派给谁，或提供 handlerId；可先询问「有哪些候选处理人」。";
                }
                HandlerResolution resolution = resolveHandler(writeTools, handlerName, context);
                if (resolution.handlerId() == null) {
                    return resolution.message();
                }
                handlerId = resolution.handlerId();
            }
        }

        // 6) 只预览，不写库
        PendingPreview previewRequest = new PendingPreview(
                intent.toolName(), intent.action(), orderId, intent.approved(), handlerId, null);
        String preview = callWriteTool(writeTools, previewRequest, context);

        // 7) 预览成功才记住令牌，供下一轮「确认执行」使用
        PendingPreview pending = withToken(previewRequest, preview);
        if (pending == null) {
            return "[mock] 写操作预览失败：\n" + preview;
        }
        pendingPreviews.put(sessionId, pending);
        return "[mock] 判定为写操作 " + intent.toolName() + "，已调用工具（预览，未写库）：\n"
                + preview
                + "\n[mock] 如需真正提交，请回复「" + writeProperties.getMockExecuteKeyword() + "」。";
    }

    /** 按动作调用对应写工具；确认时 token 非空，预览时 token 为 null */
    private String callWriteTool(WorkOrderWriteTools tools, PendingPreview pending, ToolContext context) {
        return switch (pending.action()) {
            case "REVIEW" -> tools.reviewWorkOrder(
                    pending.id(), pending.approved(), null, pending.token(), context);
            case "DISPATCH" -> tools.dispatchWorkOrder(
                    pending.id(), pending.handlerId(), null, pending.token(), context);
            case "TRANSFER" -> tools.transferWorkOrder(
                    pending.id(), pending.handlerId(), null, pending.token(), context);
            case "PROCESS" -> tools.processWorkOrder(
                    pending.id(), null, pending.token(), context);
            case "ACCEPT" -> tools.acceptWorkOrder(
                    pending.id(), pending.approved(), null, pending.token(), context);
            case "WITHDRAW" -> tools.withdrawWorkOrder(
                    pending.id(), null, pending.token(), context);
            default -> "{\"code\":400,\"message\":\"未知动作\"}";
        };
    }

    /** 从预览 JSON 中取出令牌；没有令牌（预览失败）返回 null */
    private PendingPreview withToken(PendingPreview request, String previewBody) {
        try {
            JsonNode root = objectMapper.readTree(previewBody);
            JsonNode tokenNode = root == null ? null : root.get("confirmationToken");
            if (tokenNode == null || !tokenNode.isTextual()) {
                return null;
            }
            return new PendingPreview(request.toolName(), request.action(), request.id(),
                    request.approved(), request.handlerId(), tokenNode.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /** 用候选处理人工具把姓名解析成 userId；多个候选时把列表交回给用户选择 */
    private HandlerResolution resolveHandler(WorkOrderWriteTools tools, String name, ToolContext context) {
        String body = tools.listAssignableHandlers(name, context);
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || root.isObject()) {
                return new HandlerResolution(null, "[mock] 查询候选处理人失败：\n" + body);
            }
            if (!root.isArray() || root.size() == 0) {
                return new HandlerResolution(null,
                        "[mock] 未找到匹配「" + name + "」的启用处理人，请确认姓名或直接提供 handlerId。");
            }
            if (root.size() == 1) {
                return new HandlerResolution(root.get(0).path("userId").asLong(), null);
            }
            return new HandlerResolution(null,
                    "[mock] 找到多个候选处理人，请直接提供 handlerId：\n" + body);
        } catch (Exception e) {
            return new HandlerResolution(null, "[mock] 解析候选处理人失败：" + e.getMessage());
        }
    }

    /** 写意图识别：顺序重要——转派先于派单，验收先于审核，避免关键词互相包含 */
    private static WriteIntent detectIntent(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        if (message.contains("审核") && message.contains("驳回")) {
            return new WriteIntent("reviewWorkOrder", "REVIEW", false);
        }
        if (message.contains("审核") && message.contains("通过")) {
            return new WriteIntent("reviewWorkOrder", "REVIEW", true);
        }
        if (message.contains("验收") && message.contains("退回")) {
            return new WriteIntent("acceptWorkOrder", "ACCEPT", false);
        }
        if (message.contains("验收") && message.contains("通过")) {
            return new WriteIntent("acceptWorkOrder", "ACCEPT", true);
        }
        if (message.contains("转派")) {
            return new WriteIntent("transferWorkOrder", "TRANSFER", null);
        }
        if (message.contains("派单")) {
            return new WriteIntent("dispatchWorkOrder", "DISPATCH", null);
        }
        if (message.contains("处理完成") || message.contains("处理完毕")) {
            return new WriteIntent("processWorkOrder", "PROCESS", null);
        }
        if (message.contains("撤回") || message.contains("取消工单")) {
            return new WriteIntent("withdrawWorkOrder", "WITHDRAW", null);
        }
        return null;
    }

    private static Long extractOrderId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ORDER_ID.matcher(message);
        while (matcher.find()) {
            try {
                long value = Long.parseLong(matcher.group(1));
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 继续找下一个
            }
        }
        return null;
    }

    private static Long extractHandlerId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = HANDLER_ID.matcher(message);
        if (matcher.find()) {
            try {
                return Long.valueOf(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 从「派给张伟」「转派给张伟」中取姓名；取不到返回 null */
    private static String extractHandlerName(String message) {
        if (message == null) {
            return null;
        }
        for (String marker : List.of("转派给", "派给")) {
            int index = message.indexOf(marker);
            if (index >= 0) {
                String tail = message.substring(index + marker.length()).trim();
                // 截到第一个空白或标点为止
                tail = tail.replaceAll("[\\s，。,.!！?？:：].*$", "").trim();
                return tail.isEmpty() ? null : tail;
            }
        }
        return null;
    }

    private static boolean contains(String message, String keyword) {
        return message != null && keyword != null && message.contains(keyword);
    }

    private static boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (contains(message, keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String noPerm(String toolName) {
        return "[mock] 当前用户无权使用工具 " + toolName + "（工具不可见，fail-closed）。";
    }

    /**
     * 粗略的 mock 路由：没有真实模型的 tool-calling，只能靠关键词区分「规则」与「数据」。
     * 明确的数据关键词优先；否则命中规则关键词就走知识库。
     */
    private static boolean shouldUseKnowledge(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        for (String keyword : DATA_KEYWORDS) {
            if (message.contains(keyword)) {
                return false;
            }
        }
        for (String keyword : RULE_KEYWORDS) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** mock 记录的一次写意图（token 在预览成功后才填上） */
    private record PendingPreview(String toolName, String action, Long id,
                                  Boolean approved, Long handlerId, String token) {
    }

    /** mock 识别出的写意图 */
    private record WriteIntent(String toolName, String action, Boolean approved) {
    }

    /** 候选处理人解析结果：handlerId 与提示语二选一 */
    private record HandlerResolution(Long handlerId, String message) {
    }
}
