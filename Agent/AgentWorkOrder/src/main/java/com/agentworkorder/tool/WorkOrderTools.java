package com.agentworkorder.tool;

import com.agentworkorder.audit.AuditContext;
import com.agentworkorder.audit.ToolCallAuditor;
import com.agentworkorder.audit.ToolResult;
import com.agentworkorder.client.WorkOrderToolClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 暴露给大模型的工具集（M1 只做「读」，M2 在其上加审计埋点）。
 *
 * <p>每个方法都带 {@code @Tool} / {@code @ToolParam} 描述——<b>描述质量直接决定模型会不会在正确的时机、
 * 用正确的参数调用工具</b>，是工具调用类项目里最容易被低估的一环，故写得尽量具体。
 *
 * <p>鉴权不在这里「发明」：方法从 {@link ToolContext} 里取出本轮请求的 JWT，作为<b>显式参数</b>往下传给
 * {@link WorkOrderToolClient}。这样即使工具执行线程与请求线程不是同一条，token 也不会丢。
 *
 * <p>M2 的审计埋点放在<b>工具方法边界</b>（而非 HTTP 客户端层）：只有这里能同时拿到会话、追踪、用户、
 * 模型提供方——HTTP 层看得见的只有 URL 与参数。两个 {@code @Tool} 方法都收敛到 {@link #audited} 模板，
 * 于是 mock 与 openai 两条路径（{@code MockLlmClient} 直接调本类方法）都被同一次埋点覆盖。
 */
@Component
public class WorkOrderTools {

    /**
     * {@link ToolContext} 中存放 Authorization 头值的键。
     *
     * @deprecated 已统一到 {@link ToolContextKeys#AUTHORIZATION}；保留仅为向后兼容，值一致。
     */
    @Deprecated
    public static final String AUTH_HEADER_KEY = ToolContextKeys.AUTHORIZATION;

    private final WorkOrderToolClient toolclient;
    private final ToolCallAuditor auditor;

    public WorkOrderTools(WorkOrderToolClient client, ToolCallAuditor auditor) {
        this.toolclient = client;
        this.auditor = auditor;
    }

    /**
     * 查询「当前登录用户可见的」工单列表。
     *
     * @param status    工单状态过滤（可空）
     * @param orderType 工单类型过滤（可空）
     * @param keyword   关键字过滤（可空）
     */
    @Tool(name = "listWorkOrders",
            description = "查询当前用户可见的工单列表，可按状态、工单类型、关键字过滤。"
                    + "结果最多返回 50 条；若用户想查看超过 50 条，请说明这一限制，不要谎称已全部列出。")
    public String listWorkOrders(
            @ToolParam(required = false, description = "工单状态过滤，例如「待处理」「处理中」；不传表示不限状态")
            String status,
            @ToolParam(required = false, description = "工单类型过滤，例如「报修」「投诉」；不传表示不限类型")
            String orderType,
            @ToolParam(required = false, description = "关键字过滤，匹配工单标题 / 内容等；不传表示不限")
            String keyword,
            ToolContext toolContext) {
        Map<String, Object> args = Map.of(
                "status", nullSafe(status),
                "orderType", nullSafe(orderType),
                "keyword", nullSafe(keyword));
        return audited("listWorkOrders", args, toolContext,
                () -> toolclient.listWorkOrders(status, orderType, keyword, tokenOf(toolContext)));
    }

    /**
     * 按工单 ID 查询单个工单的详细信息。
     *
     * @param id 工单 ID
     */
    @Tool(name = "queryWorkOrder",
            description = "根据工单 ID 查询单个工单的详细信息（如申请人、处理人、状态、时间等）。")
    public String queryWorkOrder(
            @ToolParam(description = "工单 ID，整数字符串")
            Long id,
            ToolContext toolContext) {
        return audited("queryWorkOrder", Map.of("id", id), toolContext,
                () -> toolclient.queryWorkOrder(id, tokenOf(toolContext)));
    }

    /**
     * 审计埋点模板：计时包裹一次工具调用，无论成功、业务失败还是抛异常都留一条流水。
     *
     * <p>关键纪律——<b>审计只旁观，不改行为</b>：成功时原样返回 {@code body}；抛异常时先记后<b>原样再抛</b>，
     * 保持与埋点前一致的异常传播语义。
     *
     * @param toolName 工具名（须与 {@code @Tool(name=...)} 一致）
     * @param args     入参（用于审计留痕）
     * @param ctx      工具上下文（抽取审计字段）
     * @param call     真正执行调用的动作
     * @return 工具调用的原始返回体
     */
    private String audited(String toolName, Map<String, Object> args, ToolContext ctx, Supplier<String> call) {
        AuditContext auditContext = AuditContext.from(ctx);
        String provider = auditContext.provider();
        long start = System.nanoTime();
        try {
            String body = call.get();
            // inspectCode：非空表示项目一返回的是错误体（HTTP 仍 200，只能看 body 判定成败）
            auditor.record(auditContext, toolName, args, body, ToolResult.inspectCode(body),
                    null, elapsedMs(start), provider);
            return body;
        } catch (RuntimeException e) {
            auditor.record(auditContext, toolName, args, null, null, e, elapsedMs(start), provider);
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 把 null 归一成空串，便于审计入参 JSON 化（{@code Map.of} 不接受 null 值）。 */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 从工具上下文中取出本轮请求的 Authorization 头值；缺席时返回空串（等价于未登录）。 */
    private static String tokenOf(ToolContext toolContext) {
        return ToolContextKeys.stringValue(toolContext, ToolContextKeys.AUTHORIZATION, "");
    }
}
