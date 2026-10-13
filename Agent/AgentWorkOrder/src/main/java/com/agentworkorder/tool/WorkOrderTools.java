package com.agentworkorder.tool;

import com.agentworkorder.client.WorkOrderToolClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 暴露给大模型的工具集（M1 只做「读」）。
 *
 * <p>每个方法都带 {@code @Tool} / {@code @ToolParam} 描述——<b>描述质量直接决定模型会不会在正确的时机、
 * 用正确的参数调用工具</b>，是工具调用类项目里最容易被低估的一环，故写得尽量具体。
 *
 * <p>鉴权不在这里「发明」：方法从 {@link ToolContext} 里取出本轮请求的 JWT，作为<b>显式参数</b>往下传给
 * {@link WorkOrderToolClient}。这样即使工具执行线程与请求线程不是同一条，token 也不会丢。
 */
@Component
public class WorkOrderTools {

    /**
     * {@link ToolContext} 中存放 Authorization 头值的键。
     * 写入方在 {@code SpringAiLlmClient#stream}（{@code .toolContext(...)}），读出方在本类，两处必须一致。
     */
    public static final String AUTH_HEADER_KEY = "authorization";

    private final WorkOrderToolClient client;

    public WorkOrderTools(WorkOrderToolClient client) {
        this.client = client;
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
        return client.listWorkOrders(status, orderType, keyword, tokenOf(toolContext));
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
        return client.queryWorkOrder(id, tokenOf(toolContext));
    }

    /** 从工具上下文中取出本轮请求的 Authorization 头值；缺席时返回空串（等价于未登录）。 */
    private static String tokenOf(ToolContext toolContext) {
        if (toolContext == null) {
            return "";
        }
        Object token = toolContext.getContext().get(AUTH_HEADER_KEY);
        return token == null ? "" : token.toString();
    }
}
