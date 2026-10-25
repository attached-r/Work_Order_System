package com.agentworkorder.sse;

import com.agentworkorder.metrics.AgentMetrics;
import com.agentworkorder.resilience.CircuitOpenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClientException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * SSE 事件工厂：把「协议细节」收在一个类里，controller 只负责编排。
 *
 * <p>为什么错误映射要单独写：异常类型很多（超时、熔断、模型 4xx、网关 5xx），
 * 但客户端只需要「一个可读消息 + 一个是否可重试的布尔值」。不要把堆栈直接丢给前端。
 */
@Slf4j
@Component
public class AgentSseEvents {

    private final ObjectMapper mapper;
    private final AgentMetrics metrics;

    public AgentSseEvents(ObjectMapper mapper, AgentMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /** 文本增量：刻意不加 event 名，保持旧客户端「只取 data」也能工作 */
    public ServerSentEvent<String> token(String token) {
        return ServerSentEvent.<String>builder(token).build();
    }

    /** 正常结束：仅此一帧，之后流关闭 */
    public ServerSentEvent<String> done(String traceId, long durationMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("traceId", traceId);
        data.put("durationMs", durationMs);
        return ServerSentEvent.<String>builder(toJson(data))
                .event("done")
                .build();
    }

    /** 错误结束：仅此一帧；客户端看到 event=error 就停止 loading */
    public ServerSentEvent<String> error(String traceId, Throwable throwable) {
        ErrorInfo info = map(throwable);
        metrics.sseError(info.code());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", info.code());
        data.put("message", info.message());
        data.put("retryable", info.retryable());
        data.put("traceId", traceId);

        return ServerSentEvent.<String>builder(toJson(data))
                .event("error")
                .build();
    }

    /** 心跳：SSE 注释行，客户端不会触发 onmessage，只用来保活 */
    public ServerSentEvent<String> heartbeat() {
        return ServerSentEvent.<String>builder().comment("hb").build();
    }

    public boolean isTerminal(ServerSentEvent<String> event) {
        return "done".equals(event.event()) || "error".equals(event.event());
    }

    private ErrorInfo map(Throwable throwable) {
        if (throwable instanceof CircuitOpenException) {
            return new ErrorInfo(503, "服务暂时繁忙，请稍后重试", true);
        }
        if (throwable instanceof TimeoutException) {
            return new ErrorInfo(504, "模型响应超时，请稍后重试", true);
        }
        if (throwable instanceof NonTransientAiException) {
            // 4xx：key / model / base-url 配错，重试也没用
            return new ErrorInfo(502, "模型服务配置异常，请联系管理员", false);
        }
        if (throwable instanceof TransientAiException
                || throwable instanceof RestClientException
                || throwable instanceof WebClientException) {
            return new ErrorInfo(502, "模型服务暂时不可用，请稍后重试", true);
        }
        return new ErrorInfo(500, "服务内部错误，请稍后重试", true);
    }

    private String toJson(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            // 只含基本类型，正常不会失败；兜底一个合法 JSON
            return "{\"code\":500,\"message\":\"事件序列化失败\"}";
        }
    }

    /** 错误映射结果：给客户端的最小信息 */
    private record ErrorInfo(int code, String message, boolean retryable) {
    }
}
