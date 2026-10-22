package com.agentworkorder.config;

import org.springframework.stereotype.Component;

/**
 * 系统提示词组合器（M4）：基础段 + 可选 RAG 段 + 可选写操作段。
 * <p>
 * 只按<b>实际注册的工具</b>追加对应段落：写开关关闭时提示词不含写规则，
 * RAG 关闭时不含检索规则，避免模型尝试调用不存在的工具。
 */
@Component
public class SystemPromptComposer {

    private final LlmProperties properties;

    public SystemPromptComposer(LlmProperties properties) {
        this.properties = properties;
    }

    /**
     * @param ragEnabled   searchKnowledge 是否已注册
     * @param writeEnabled 写工具是否已注册
     */
    public String compose(boolean ragEnabled, boolean writeEnabled) {
        StringBuilder prompt = new StringBuilder(properties.getBasePrompt().trim());
        if (ragEnabled) {
            prompt.append("\n\n").append(properties.getRagPrompt().trim());
        }
        if (writeEnabled) {
            prompt.append("\n\n").append(properties.getWritePrompt().trim());
        }
        return prompt.toString();
    }
}
