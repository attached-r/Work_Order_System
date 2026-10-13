package com.agentworkorder.client;

import com.agentworkorder.config.WorkOrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * 直连项目一 {@code /tool/**} 的 HTTP 客户端（用 Spring 6 自带的 {@link RestClient}，不引 Spring Cloud / Feign）。
 *
 * <p>两个约定（对齐项目一 Tool 接口契约，见设计文档 §6.1 / §6.4）：
 * <ol>
 *   <li><b>返回原始响应体 {@code String}</b>，不做强类型反序列化——成功是裸 JSON、失败是 {@code {code,message}}，
 *       结构不固定，交给大模型自行解读比强行映射更稳；</li>
 *   <li><b>透传用户 JWT</b>——调用方把完整的 {@code Authorization} 头值一路传进来，这里原样带上；
 *       项目一的 {@code LoginInterceptor} 据此识别身份并收敛数据范围，agent 侧不关心权限逻辑。</li>
 * </ol>
 */
@Component
public class WorkOrderToolClient {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderToolClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public WorkOrderToolClient(WorkOrderProperties properties) {
        this.baseUrl = properties.getBaseUrl();
        this.restClient = RestClient.builder().baseUrl(this.baseUrl).build();
    }

    /**
     * 查询工单列表。
     *
     * @return 项目一返回的原始 JSON（通常是 {@code PageResult} 的裸数据；失败为精简错误体）
     */
    public String listWorkOrders(String status, String orderType, String keyword, String authToken) {
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

    /**
     * 按 ID 查询单个工单详情。
     *
     * @return 项目一返回的原始 JSON（{@code WorkOrderDetailVO} 裸数据；失败为精简错误体）
     */
    public String queryWorkOrder(Long id, String authToken) {
        log.info("[tool] GET {}/tool/workorder/{}", baseUrl, id);

        RestClient.RequestHeadersSpec<?> spec =
                restClient.get().uri("/tool/workorder/{id}", id);

        String body = withAuth(spec, authToken).retrieve().body(String.class);
        log.info("[tool] 项目一返回：{} 字节", body == null ? 0 : body.length());
        return body;
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
