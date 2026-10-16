package com.agentworkorder.audit;

import com.agentworkorder.tool.ToolContextKeys;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 审计上下文：从 Spring AI 的 {@link ToolContext} 里抽出的「谁、在哪轮、由哪个提供方」标识。
 *
 * <p>埋点放在 {@code @Tool} 方法边界，只有这里能同时拿到会话、追踪、用户、提供方等信息
 * （HTTP 客户端 {@code WorkOrderToolClient} 层拿不到这些）。本类把这堆字段从上下文映射出来，
 * 让审计服务只依赖一个干净的入参对象，而不必到处 {@code getContext().get(...)}。
 *
 * @param sessionId 会话 id
 * @param traceId   本轮追踪 id
 * @param userId    用户 id，可空
 * @param username  用户名，可空
 * @param provider  模型提供方（mock / openai），可空
 */
public record AuditContext(String sessionId, String traceId, Long userId, String username, String provider) {

    /** 从工具上下文抽取审计字段；{@code toolContext} 为 {@code null} 时各字段留空。 */
    public static AuditContext from(ToolContext toolContext) {
        if (toolContext == null) {
            return new AuditContext(null, null, null, null, null);
        }
        return new AuditContext(
                ToolContextKeys.stringValue(toolContext, ToolContextKeys.SESSION_ID, null),
                ToolContextKeys.stringValue(toolContext, ToolContextKeys.TRACE_ID, null),
                ToolContextKeys.longValue(toolContext, ToolContextKeys.USER_ID),
                ToolContextKeys.stringValue(toolContext, ToolContextKeys.USERNAME, null),
                ToolContextKeys.stringValue(toolContext, ToolContextKeys.PROVIDER, null));
    }
}
