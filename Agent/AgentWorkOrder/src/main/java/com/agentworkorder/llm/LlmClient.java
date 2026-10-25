package com.agentworkorder.llm;

import reactor.core.publisher.Flux;

/**
 * 大模型能力的「端口」（端口 / 适配器模式里的端口）。
 *
 * <p>这层刻意只暴露业务语义、不泄漏任何 Spring AI 类型（没有 {@code ChatClient}、没有 {@code ChatModel}）。
 * 上层（{@code AgentChatController}）只依赖本接口，于是「换提供方 / 换模型」时，控制器与工具代码一行都不用改，
 * 只需换一个实现（见 {@link SpringAiLlmClient} / {@link MockLlmClient}）。
 */
public interface LlmClient {

    /**
     * 以流式方式回答用户消息，逐段产出文本（token）。
     *
     * @param sessionId   会话 id（审计仍用它；记忆键由实现加 userId 前缀）
     * @param userMessage 用户这一轮的提问
     * @param authToken   用户 JWT（完整 Authorization 头值）；透传给项目一鉴权，可能为 {@code null}（未登录）
     * @param traceId     本轮追踪 id；由 controller 生成，贯穿 SSE / 工具审计 / 日志
     * @return 逐段产生的回答文本
     */
    Flux<String> stream(String sessionId, String userMessage, String authToken, String traceId);
}
