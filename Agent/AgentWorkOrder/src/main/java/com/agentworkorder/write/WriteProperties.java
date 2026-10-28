package com.agentworkorder.write;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 写操作相关配置，绑定 {@code agent.write.*}（M4 建立，M5 增加令牌存储实现）。
 * <p>
 * {@code enabled=false} 时写工具 Bean 不注册、系统提示词也不含写规则，
 * 行为退回 M3 的纯只读形态。
 * <p>
 * M5 起 {@link #tokenStore} 决定确认令牌放进程内还是 Redis：
 * 单实例可用 memory，多实例必须 redis（否则预览在 A、确认到 B 会失败）。
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

    /** memory=进程内（回滚用）；redis=Redis（默认，支持多实例）。 */
    private String tokenStore = "memory";

    /** 写令牌 Redis key 前缀；值里不含 JWT。 */
    private String tokenKeyPrefix = "agent:write:pending:";
}
