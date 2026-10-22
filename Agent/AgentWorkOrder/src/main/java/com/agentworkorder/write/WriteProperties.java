package com.agentworkorder.write;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 写操作相关配置，绑定 {@code agent.write.*}（M4）。
 * <p>
 * {@code enabled=false} 时写工具 Bean 不注册、系统提示词也不含写规则，
 * 行为退回 M3 的纯只读形态。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "agent.write")
public class WriteProperties {

    /** 写能力总开关 */
    private boolean enabled = true;

    /** 预览令牌有效期（秒）：预览到确认的最大间隔 */
    private int confirmTtlSeconds = 300;

    /** 进程内待确认预览上限，防止内存无界增长 */
    private int maxPreviewEntries = 2000;

    /** 每轮对话最多执行的真实写操作数；0 = 只允许预览 */
    private int maxActionsPerTurn = 1;

    /** mock 模式下真正提交所需的关键词（避免闲聊里的「确认」误触发写入） */
    private String mockExecuteKeyword = "确认执行";
}
