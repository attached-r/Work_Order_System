package com.agentworkorder.tool;

import com.agentworkorder.perm.UserPerms;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring AI {@link ToolContext} 的键常量与统一构造工厂。
 *
 * <p>为什么要把「往上下文里塞什么」收拢到一个类：工具执行线程与请求线程不保证是同一条，
 * 因此鉴权、会话、追踪等所有跨线程需要的信息都只能<b>显式</b>放进 {@code ToolContext} 往下传。
 * 若写入方（{@code SpringAiLlmClient} / {@code MockLlmClient}）与读取方（{@code WorkOrderTools}）
 * 各自写死字符串键，一旦某处拼写或漏填，就会静默地少一个字段——审计日志里表现为「字段莫名为空」。
 * 故用本类的 {@link #build} 作为<b>唯一入口</b>，禁止两处各写 {@code Map.of}。
 *
 * <p>注意 {@link #AUTHORIZATION} 的字符串值沿用 M1 {@code WorkOrderTools.AUTH_HEADER_KEY}，
 * 保持向后兼容（旧的下游读取逻辑不受影响）。
 */
public final class ToolContextKeys {

    /** 完整 {@code Authorization} 头值（用户 JWT）。值须与 M1 的 {@code WorkOrderTools.AUTH_HEADER_KEY} 一致。 */
    public static final String AUTHORIZATION = "authorization";

    /** 会话 id，多轮记忆的键。 */
    public static final String SESSION_ID = "sessionId";

    /** 一次对话请求的追踪 id，同一轮内所有工具调用共享。 */
    public static final String TRACE_ID = "traceId";

    /** 用户 id（取自项目一 {@code /tool/user/info}）；未登录时为 {@code null}。 */
    public static final String USER_ID = "userId";

    /** 用户名（取自项目一 {@code /tool/user/info}）；未登录时为 {@code null}。 */
    public static final String USERNAME = "username";

    /** 模型提供方（{@code mock} / {@code openai}），仅用于审计留痕。 */
    public static final String PROVIDER = "provider";

    private ToolContextKeys() {
    }

    /**
     * 构造本轮请求的工具上下文。
     *
     * <p>用 {@link HashMap} 而非 {@code Map.of}：后者不接受 {@code null} 值，而 {@code userId} 在未登录时
     * 就是 {@code null}，{@code authToken} 也可能为空——这些「合法的空」必须能被原样带下去。
     *
     * @param authToken 用户 JWT（完整 Authorization 头值），可空
     * @param sessionId 会话 id
     * @param traceId   本轮追踪 id
     * @param userPerms 用户权限（可能为 {@link UserPerms#ANONYMOUS}），仅取其 userId
     * @param provider  模型提供方（mock / openai）
     * @return 传给 {@code ChatClient...toolContext(...)} 的上下文
     */
    public static Map<String, Object> build(String authToken, String sessionId, String traceId,
                                            UserPerms userPerms, String provider) {
        Map<String, Object> context = new HashMap<>();
        context.put(AUTHORIZATION, authToken == null ? "" : authToken);
        context.put(SESSION_ID, sessionId);
        context.put(TRACE_ID, traceId);
        context.put(USER_ID, userPerms == null ? null : userPerms.userId());
        context.put(USERNAME, userPerms == null ? null : userPerms.username());
        context.put(PROVIDER, provider);
        return context;
    }

    /** 从上下文中取值并转成字符串；缺席时返回给定的默认值。 */
    public static String stringValue(ToolContext toolContext, String key, String defaultValue) {
        if (toolContext == null) {
            return defaultValue;
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? defaultValue : value.toString();
    }

    /** 从上下文中取 {@code userId}（{@code Long}）；缺席或非数字时返回 {@code null}。 */
    public static Long longValue(ToolContext toolContext, String key) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
