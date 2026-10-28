package com.agentworkorder.client;

import com.agentworkorder.config.WorkOrderProperties;
import com.agentworkorder.resilience.ResilienceGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 直连项目一 {@code /tool/**} 的 HTTP 客户端（用 Spring 6 自带的 {@link RestClient}，不引 Spring Cloud / Feign）。
 *
 * <p>两个约定（对齐项目一 Tool 接口契约）：
 * <ol>
 *   <li><b>返回原始响应体 {@code String}</b>，不做强类型反序列化——成功是裸 JSON、失败是 {@code {code,message}}，
 *       结构不固定，交给大模型自行解读比强行映射更稳；</li>
 *   <li><b>透传用户 JWT</b>——调用方把完整的 {@code Authorization} 头值一路传进来，这里原样带上；
 *       项目一的 {@code LoginInterceptor} 据此识别身份并收敛数据范围，agent 侧不关心权限逻辑。</li>
 * </ol>
 *
 * <p>M5 起每个方法都经过 {@link ResilienceGuard}：
 * <ul>
 *   <li>熔断打开 → 直接返回 {@code 503} 错误体，不再等超时；</li>
 *   <li>网络异常 → 读接口返回「服务暂不可用」，写接口返回「结果未知」；</li>
 *   <li>所有 fallback 都是 {@code {code,message}}，M2 审计会自动标成 ERROR。</li>
 * </ul>
 */
@Component
public class WorkOrderToolClient {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderToolClient.class);

    /** 下游资源名：所有 /tool/** 共用同一个熔断器（同一个项目一）。 */
    private static final String RESOURCE = "project-one";

    private final RestClient restClient;
    private final String baseUrl;
    private final ResilienceGuard resilience;
    private final ObjectMapper objectMapper;

    public WorkOrderToolClient(WorkOrderProperties properties,
                               ResilienceGuard resilience,
                               ObjectMapper objectMapper) {
        this.baseUrl = properties.getBaseUrl();
        this.resilience = resilience;
        this.objectMapper = objectMapper;

        // 显式超时：不能让一次网络故障把整轮对话挂死。
        // 写操作是项目一的本地事务，10 秒足够；超时后由上层转成「结果未知」，绝不自动重试。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 查询工单列表（读）。
     *
     * @return 项目一返回的原始 JSON；熔断/网络异常时返回 {@code {code:503}} 错误体
     */
    public String listWorkOrders(String status, String orderType, String keyword, String authToken) {
        return resilience.call(RESOURCE,
                () -> doListWorkOrders(status, orderType, keyword, authToken),
                () -> errorBody(503, "工单服务暂不可用，请稍后重试"),
                e -> errorBody(503, "工单服务暂不可用，请稍后重试"));
    }

    /**
     * 按 ID 查询单个工单详情（读）。
     *
     * @return 项目一返回的原始 JSON；熔断/网络异常时返回 {@code {code:503}} 错误体
     */
    public String queryWorkOrder(Long id, String authToken) {
        return resilience.call(RESOURCE,
                () -> doQueryWorkOrder(id, authToken),
                () -> errorBody(503, "工单服务暂不可用，请稍后重试"),
                e -> errorBody(503, "工单服务暂不可用，请稍后重试"));
    }

    /**
     * 查询当前登录用户的身份与权限（{@code GET /tool/user/info}，无参数）。
     *
     * <p>失败时返回 503 错误体；PermService 会把它识别为错误体并按匿名处理（fail-closed）。
     */
    public String getUserInfo(String authToken) {
        return resilience.call(RESOURCE,
                () -> doGetUserInfo(authToken),
                () -> errorBody(503, "权限服务暂不可用，请稍后重试"),
                e -> errorBody(503, "权限服务暂不可用，请稍后重试"));
    }

    /**
     * 执行一次工单动作（写）。
     *
     * <p>写操作的两套 fallback 必须区分：
     * <ul>
     *   <li>熔断打开：请求一定没发出去 → 「未提交」；</li>
     *   <li>调用异常：请求可能已经到了项目一 → 「结果未知，请先查询」。</li>
     * </ul>
     */
    public String changeStatus(Long id, ToolChangeStatusRequest request, String authToken) {
        return resilience.call(RESOURCE,
                () -> doChangeStatus(id, request, authToken),
                () -> errorBody(503, "写操作未提交：工单服务暂不可用，请稍后重新预览"),
                e -> errorBody(500, "写操作结果未知，请先查询工单当前状态再决定是否重试"));
    }

    /**
     * 查询可指派处理人（{@code GET /tool/user/handlers}）。
     *
     * @param keyword 姓名/账号关键字，可空；由项目一按本部门 + HANDLER + 启用状态过滤
     * @return 裸 JSON 数组；失败为 {@code {code,message}} 错误体
     */
    public String listHandlers(String keyword, String authToken) {
        return resilience.call(RESOURCE,
                () -> doListHandlers(keyword, authToken),
                () -> errorBody(503, "工单服务暂不可用，请稍后重试"),
                e -> errorBody(503, "工单服务暂不可用，请稍后重试"));
    }

    // ------------------------------------------------------------------
    // 真正的 HTTP 调用（只在熔断器放行后执行）
    // ------------------------------------------------------------------

    private String doListWorkOrders(String status, String orderType, String keyword, String authToken) {
        log.info("[tool] GET {}/tool/workorder/list status={} orderType={} keyword={}",
                baseUrl, status, orderType, keyword);

        RestClient.RequestHeadersSpec<?> spec = restClient.get()
                .uri(builder -> builder.path("/tool/workorder/list")
                        .queryParamIfPresent("status", present(status))
                        .queryParamIfPresent("orderType", present(orderType))
                        .queryParamIfPresent("keyword", present(keyword))
                        .build());

        String body = withAuth(spec, authToken).retrieve().body(String.class);
        log.info("[tool] 项目一返回：{} 字节", body == null ? 0 : body.length());
        return body;
    }

    private String doQueryWorkOrder(Long id, String authToken) {
        log.info("[tool] GET {}/tool/workorder/{}", baseUrl, id);

        RestClient.RequestHeadersSpec<?> spec =
                restClient.get().uri("/tool/workorder/{id}", id);
        String body = withAuth(spec, authToken).retrieve().body(String.class);
        log.info("[tool] 项目一返回：{} 字节", body == null ? 0 : body.length());
        return body;
    }

    private String doGetUserInfo(String authToken) {
        log.info("[tool] GET {}/tool/user/info", baseUrl);

        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri("/tool/user/info");
        String body = withAuth(spec, authToken).retrieve().body(String.class);
        log.info("[tool] 项目一返回：{} 字节", body == null ? 0 : body.length());
        return body;
    }

    private String doChangeStatus(Long id, ToolChangeStatusRequest request, String authToken) {
        log.info("[tool] PUT {}/tool/workorder/{}/status action={} expectedFrom={}",
                baseUrl, id, request.action(), request.expectedFromStatus());

        RestClient.RequestHeadersSpec<?> spec = restClient.put()
                .uri("/tool/workorder/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request);

        String body = withAuth(spec, authToken).retrieve().body(String.class);
        log.info("[tool] 项目一返回：{} 字节", body == null ? 0 : body.length());
        return body;
    }

    private String doListHandlers(String keyword, String authToken) {
        log.info("[tool] GET {}/tool/user/handlers keyword={}", baseUrl, keyword);

        RestClient.RequestHeadersSpec<?> spec = restClient.get()
                .uri(builder -> builder.path("/tool/user/handlers")
                        .queryParamIfPresent("keyword", present(keyword))
                        .build());

        String body = withAuth(spec, authToken).retrieve().body(String.class);
        log.info("[tool] 项目一返回：{} 字节", body == null ? 0 : body.length());
        return body;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 构造与项目一错误体同形状的 JSON，交给模型转述、让审计判 ERROR。 */
    private String errorBody(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            // 只含字符串/数字，正常不会失败；兜底一个合法 JSON
            return "{\"code\":" + code + ",\"message\":\"服务暂不可用\"}";
        }
    }

    /** 只有在 token 非空时才加 Authorization 头，避免把 {@code "null"} 写进请求头。 */
    private RestClient.RequestHeadersSpec<?> withAuth(RestClient.RequestHeadersSpec<?> spec, String authToken) {
        if (StringUtils.hasText(authToken)) {
            return spec.header(HttpHeaders.AUTHORIZATION, authToken);
        }
        return spec;
    }

    /** 把「空串」归一成 empty，便于 {@code queryParamIfPresent} 跳过无意义的查询参数。 */
    private static Optional<String> present(String value) {
        return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
    }
}
