package com.agentworkorder.controller;

import com.agentworkorder.llm.LlmClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Agent 对外唯一入口：SSE 流式对话端点。
 *
 * <p>职责刻意保持很薄——只做三件事：取参数、取 Authorization 头、把 {@link LlmClient} 的输出流
 * 逐段映射成 SSE 事件。它<b>不碰</b> Spring AI、也不碰 HTTP 工具细节，全靠 {@code LlmClient} 端口隔离。
 *
 * <p>用法：{@code GET /api/agent/chat?sessionId=s1&message=...}，带 {@code Authorization} 头。
 * 返回 {@code text/event-stream}，每个 token 一个事件，客户端逐字渲染。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private final LlmClient llmClient;

    public AgentChatController(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam String sessionId,
            @RequestParam String message,
            // 用户 JWT：完整 Authorization 头值，原样透传给项目一（可能缺省=未登录）
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return llmClient.stream(sessionId, message, authorization)
                .map(token -> ServerSentEvent.builder(token).build());
    }
}
