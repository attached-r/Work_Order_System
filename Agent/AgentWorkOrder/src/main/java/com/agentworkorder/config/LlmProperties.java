package com.agentworkorder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大模型相关配置，绑定 application.yaml 中的 {@code agent.llm.*}。
 *
 * <p>M1 只保留两个开关：
 * <ul>
 *   <li>{@link #provider} —— 决定注入哪个 {@code LlmClient} 适配器（{@code mock} / {@code openai}），
 *       是整个「换模型」动作的唯一入口；</li>
 *   <li>{@link #systemPrompt} —— 交给大模型的系统提示词。</li>
 * </ul>
 *
 * <p>注意：模型名 / base-url / api-key 等「同一提供方内部调参」的项，直接复用 Spring AI 原生的
 * {@code spring.ai.openai.*}（见 application.yaml），不在这里重复声明，避免两套配置源。
 */
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProperties {

    /**
     * 适配器选择：
     * <ul>
     *   <li>{@code mock}（默认）—— 不调真模型，无需 API key 即可跑通整条链路；</li>
     *   <li>{@code openai} —— 走真实大模型（OpenAI 兼容协议，可指向通义千问兼容模式 / DeepSeek 等）。</li>
     * </ul>
     */
    private String provider = "mock";

    /**
     * 系统提示词。质量直接决定模型会不会在正确时机、用正确参数调用工具，故单独抽出来可调。
     */
    private String systemPrompt = """
            你是「智能工单系统」的助手。
            当用户询问工单时，必须先调用工具获取真实数据，再基于数据进行回答，绝不编造数据。
            工单列表最多返回 50 条；若用户想要更多，请明确告知这一限制。
            请始终使用中文、简洁作答。""";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
