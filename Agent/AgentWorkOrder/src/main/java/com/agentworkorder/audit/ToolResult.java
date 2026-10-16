package com.agentworkorder.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 判断一次工具调用「成功还是业务失败」的小工具。
 *
 * <p>为什么需要它：项目一 {@code /tool/**} 的 <b>HTTP 状态恒为 200</b>（成功与业务错误都是 200，
 * 见 {@code ToolExceptionHandler}），因此无法用状态码判断成败，<b>只能看 body</b>。
 * 判定规则：把 body 解析成 JSON，若<b>顶层同时存在 {@code code} 与 {@code message} 两个字段</b>
 * 就判为错误体（这是 {@code ToolErrorVO} 的确切形状，误判概率最低），并返回其 {@code code}；
 * 否则视为成功（返回 {@code null}）。
 *
 * <p>成功体是<b>裸 JSON，不套 code</b>（{@code PageResult} / {@code WorkOrderDetailVO} 等），
 * 故不会与错误体混淆。
 *
 * <p>注：agent 是 Boot 3.5.15 → Jackson <b>2</b>（{@code com.fasterxml.jackson.*}）。
 * 这里用私有 {@link ObjectMapper} 即可，解析只读两个字段、无特殊配置需求，无需引入 Spring 容器。
 */
public final class ToolResult {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResult() {
    }

    /**
     * 解析返回体，判断是否为项目一的错误体。
     *
     * @param body 项目一返回的原始响应体
     * @return 错误体的 {@code code}（如 401/403/404/500）；非错误体（=成功）返回 {@code null}
     */
    public static Integer inspectCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isObject() && root.hasNonNull("code") && root.hasNonNull("message")) {
                // 契约里 code 恒为整数，asInt 对数字/数字字符串都适用
                return root.get("code").asInt();
            }
        } catch (Exception e) {
            // 非 JSON（成功体也可能不是 JSON）——不视为失败
        }
        return null;
    }
}
