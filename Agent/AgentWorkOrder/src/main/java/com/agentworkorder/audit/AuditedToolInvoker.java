package com.agentworkorder.audit;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 共享的工具审计模板（M2 埋点的无行为变化抽取，M3 新增 searchKnowledge 后为两套工具复用）。
 *
 * <p>为什么放在 {@code @Tool} 方法边界：只有这里能同时拿到会话、追踪、用户、模型提供方；
 * HTTP 客户端层只看得到 URL 与参数。
 *
 * <p>关键纪律——<b>审计只旁观，不改行为</b>：
 * <ul>
 *   <li>成功时原样返回 {@code body}；</li>
 *   <li>业务错误体（项目一 HTTP 恒 200）由 {@link ToolResult#inspectCode(String)} 判定；</li>
 *   <li>抛异常时先记一条 ERROR，再把异常<b>原样抛出</b>，保持与埋点前一致的传播语义。</li>
 * </ul>
 */
@Component
public class AuditedToolInvoker {

    private final ToolCallAuditor auditor;

    public AuditedToolInvoker(ToolCallAuditor auditor) {
        this.auditor = auditor;
    }

    /**
     * 包裹一次工具调用并写入审计流水。
     *
     * @param toolName 工具名（须与 {@code @Tool(name=...)} 一致）
     * @param args     入参（用于审计留痕）
     * @param ctx      工具上下文（抽取审计字段）
     * @param call     真正执行调用的动作
     * @return 工具调用的原始返回体
     */
    public String invoke(String toolName, Map<String, Object> args, ToolContext ctx, Supplier<String> call) {
        return invoke(toolName, args, ctx, call, body -> body);
    }

    /**
     * 带「审计脱敏」的重载：返回给模型的内容不变，但落库前先经过 {@code auditBodySanitizer}。
     * <p>
     * M4 的预览响应含完整确认令牌，令牌不应完整落盘；工具层用本重载把令牌截成前缀。
     *
     * @param auditBodySanitizer 仅作用于审计摘要，不影响工具返回值
     */
    public String invoke(String toolName, Map<String, Object> args, ToolContext ctx,
                         Supplier<String> call, UnaryOperator<String> auditBodySanitizer) {
        // 步骤 1：从 ToolContext 抽取「谁、在哪轮、由哪个提供方」，并记录起始时间
        AuditContext auditContext = AuditContext.from(ctx);
        String provider = auditContext.provider();
        long start = System.nanoTime();

        try {
            // 步骤 2：执行真正的工具调用
            String body = call.get();

            // 步骤 3：写审计。错误码按原 body 判断，摘要用脱敏后的 body
            auditor.record(auditContext, toolName, args, sanitize(body, auditBodySanitizer),
                    ToolResult.inspectCode(body), null, elapsedMs(start), provider);

            // 步骤 4：原样返回，不改变调用语义
            return body;
        } catch (RuntimeException e) {
            // 步骤 5：传输层异常也留痕，然后把异常原样抛出
            auditor.record(auditContext, toolName, args, null, null, e, elapsedMs(start), provider);
            throw e;
        }
    }

    /** 脱敏失败时退化为不记录摘要（宁可少记，也不能让审计影响主流程） */
    private static String sanitize(String body, UnaryOperator<String> sanitizer) {
        if (body == null) {
            return null;
        }
        try {
            return sanitizer.apply(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
