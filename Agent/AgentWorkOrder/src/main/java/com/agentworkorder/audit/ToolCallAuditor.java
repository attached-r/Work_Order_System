package com.agentworkorder.audit;

import com.agentworkorder.audit.mapper.AgentToolCallLogMapper;
import com.agentworkorder.config.AuditProperties;
import com.agentworkorder.metrics.AgentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工具调用审计服务：把一次 {@code @Tool} 调用落成 {@code agent_tool_call_log} 的一行。
 *
 * <p><b>硬性纪律——审计绝不能拖垮主流程</b>：写库可能因表不存在、连接池满、字段超长等失败，
 * 但这些都<b>不能</b>让用户的对话失败。因此本类对外只暴露 {@link #record}，且内部：
 * <ol>
 *   <li>先看 {@code agent.audit.enabled} 开关，关掉直接返回（压测时剥离审计开销）；</li>
 *   <li>把所有字符串字段<b>按列宽截断</b>，避免 {@code Data too long} 把一条审计变成异常；</li>
 *   <li>把 {@code mapper.insert(...)} 包在 {@code try/catch(Exception)} 里，<b>只告警、不抛出</b>。</li>
 * </ol>
 */
@Slf4j
@Service
public class ToolCallAuditor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与表列宽一致的截断上限：{@code tool_args} / {@code error_message} 为 1000 / 512。 */
    private static final int ARGS_MAX = 1000;
    private static final int ERROR_MAX = 512;

    private final AgentToolCallLogMapper mapper;
    private final AuditProperties properties;
    private final AgentMetrics metrics;

    public ToolCallAuditor(AgentToolCallLogMapper mapper, AuditProperties properties, AgentMetrics metrics) {
        this.mapper = mapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 记录一次工具调用。本方法<b>不抛异常</b>——审计只旁观，成败都不影响调用方。
     *
     * @param ctx        审计上下文（会话 / 追踪 / 用户 / 提供方）
     * @param toolName   工具名
     * @param args       工具入参（可为空）
     * @param body       项目一返回体（传输层异常时为 {@code null}）
     * @param resultCode 业务错误体的 code（成功为 {@code null}）
     * @param error      传输层异常（成功为 {@code null}）
     * @param latencyMs  调用耗时（毫秒）
     * @param provider   模型提供方
     */
    public void record(AuditContext ctx, String toolName, Map<String, Object> args,
                       String body, Integer resultCode, Throwable error,
                       long latencyMs, String provider) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            AgentToolCallLog entry = new AgentToolCallLog();
            entry.setTraceId(ctx.traceId());
            entry.setSessionId(ctx.sessionId());
            entry.setUserId(ctx.userId());
            entry.setUsername(ctx.username());
            entry.setToolName(toolName);
            entry.setToolArgs(truncate(toJson(args), ARGS_MAX));
            // 业务错误（有 resultCode）或传输异常（有 error）都算 ERROR
            boolean failed = resultCode != null || error != null;
            entry.setCallStatus(failed ? "ERROR" : "SUCCESS");
            entry.setResultCode(resultCode);
            entry.setResultExcerpt(truncate(body, properties.getExcerptMaxLength()));
            entry.setResultLength(body == null ? null : body.length());
            entry.setLatencyMs(latencyMs);
            entry.setErrorMessage(truncate(error == null ? null : error.getMessage(), ERROR_MAX));
            entry.setProvider(provider);

            mapper.insert(entry);
        } catch (Exception e) {
            // 审计失败只告警：绝不能让它影响对话主流程
            metrics.auditFailure();
            log.warn("审计写入失败（不影响主流程）：tool={} trace={} err={}", toolName, ctx.traceId(), e.toString());
        }
    }

    /** 入参 Map → JSON 字符串；失败则退化为 {@code toString()}（审计内容是尽力而为，不追求完美）。 */
    private static String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(args);
        } catch (Exception e) {
            return args.toString();
        }
    }

    /** 超过上限则截断；省略号占用配额，保证结果总长 ≤ {@code max}（不越列宽）。 */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        if (max <= 3) {
            return value.substring(0, max);
        }
        return value.substring(0, max - 3) + "...";
    }
}
