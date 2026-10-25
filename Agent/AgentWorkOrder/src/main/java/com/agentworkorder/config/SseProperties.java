package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 协议相关配置，绑定 {@code agent.sse.*}（M5）。
 *
 * <p>三个旋钮分别对应：
 * <ul>
 *   <li>{@link #heartbeatSeconds}：多久发一个 SSE 注释心跳，防止网关空闲超时；</li>
 *   <li>{@link #idleTimeoutSeconds}：模型多久没有任何输出就判超时，发 error 事件；</li>
 *   <li>{@link #maxMessageChars}：用户输入长度上限，避免超长 query / 上下文膨胀。</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.sse")
public class SseProperties {

    /** 心跳间隔（秒）；必须大于 0 */
    private int heartbeatSeconds = 15;

    /** 空闲超时（秒）；必须大于 0 */
    private int idleTimeoutSeconds = 60;

    /** 用户消息最大字符数 */
    private int maxMessageChars = 4000;
}
