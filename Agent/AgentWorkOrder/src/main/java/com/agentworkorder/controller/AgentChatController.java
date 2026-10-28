package com.agentworkorder.controller;

import com.agentworkorder.config.SseProperties;
import com.agentworkorder.llm.LlmClient;
import com.agentworkorder.sse.AgentSseEvents;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 对外唯一入口：SSE 流式对话（M5 协议 v2）。
 *
 * <p>M5 加了四件事：
 * <ol>
 *   <li>sessionId / message 入参校验（也顺手防了超长 Redis key）；</li>
 *   <li>统一 traceId，并通过响应头 X-Trace-Id 返回；</li>
 *   <li>空闲超时 → error 事件；正常结束 → done 事件；</li>
 *   <li>每 15 秒一个 SSE 注释心跳，防止网关掐连接。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    /** sessionId 只允许这些字符：既能做 Redis key，也不会造成 key 注入 */
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final LlmClient llmClient;
    private final AgentSseEvents events;
    private final SseProperties properties;

    public AgentChatController(LlmClient llmClient, AgentSseEvents events, SseProperties properties) {
        this.llmClient = llmClient;
        this.events = events;
        this.properties = properties;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletResponse response) {

        // 步骤 1：同步校验。校验失败直接 400，不进入 SSE（客户端拿到普通 JSON 错误）
        if (!SESSION_ID.matcher(sessionId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sessionId 只能包含字母、数字、下划线、连字符，长度 1-64");
        }
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        if (message.length() > properties.getMaxMessageChars()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "message 不能超过 " + properties.getMaxMessageChars() + " 字");
        }

        // 步骤 2：一轮对话一个 traceId；响应头先给客户端，后面 done/error 里也有
        String traceId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");   // Nginx 不缓冲

        // 步骤 3：Flux.defer 很关键——让 llmClient.stream 里的同步异常也变成流上的 error，
        //         从而被下面的 onErrorResume 捕获，而不是在 controller 返回前直接抛出。
        Flux<ServerSentEvent<String>> body = Flux.defer(() ->  //被订阅时 执行
                        llmClient.stream(sessionId, message, authorization, traceId))
                .map(events::token) // 把 Flux<String> 包装成 Flux<ServerSentEvent<String>>
                .timeout(Duration.ofSeconds(Math.max(1, properties.getIdleTimeoutSeconds())))
                .concatWith(Mono.fromSupplier(() -> // 消息发送给完毕 追加Mono消息
                        events.done(traceId, System.currentTimeMillis() - start)))
                // 放在 concatWith 之后：正常路径会发 done；异常路径只发 error，不会再补 done
                .onErrorResume(e -> {
                    // 日志保留完整堆栈；发给客户端的只有脱敏后的 code/message
                    log.error("SSE 流异常：trace={}", traceId, e);
                    return Flux.just(events.error(traceId, e));
                });

        // 步骤 4：心跳与正文合并；takeUntil 保证看到 done/error 就取消心跳并正常结束
        Flux<ServerSentEvent<String>> heartbeat = Flux
                .interval(Duration.ofSeconds(Math.max(1, properties.getHeartbeatSeconds())))
                .map(i -> events.heartbeat());

        return Flux.merge(body, heartbeat)
                .takeUntil(events::isTerminal)
                .doOnCancel(() -> log.info("客户端断开：trace={}", traceId));
    }

    /**
     * 连通性自测（调试用）：走同一个 LlmClient，阻塞收集全部 token。
     * <p>M5 只补了 traceId 与 60 秒上限；它不是产品接口。
     */
    @GetMapping("/hello")
    public String hello(@RequestParam String context) {
        String traceId = UUID.randomUUID().toString();
        return llmClient.stream("hello-test", context, null, traceId)
                .collectList()
                .map(tokens -> String.join("", tokens))
                .block(Duration.ofSeconds(60));
    }
}
