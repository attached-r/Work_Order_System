package com.agentworkorder.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计日志实体，对应表 {@code agent_tool_call_log}。
 *
 * <p>设计上<b>只增不改</b>：审计日志天然 append-only，因此没有 {@code deleted} / {@code updateTime}
 * （对齐项目一「逻辑删除只用于可变主数据」的原则），{@code createTime} 由数据库 {@code DEFAULT CURRENT_TIMESTAMP} 落库。
 *
 * <p>字段是 camelCase，MyBatis-Plus 按 {@code map-underscore-to-camel-case} 自动映射到 snake_case 列。
 * 这是「agent 唯一自己持有的数据表」——只存工具调用流水，不存任何业务事实。
 */
@Data
@TableName("agent_tool_call_log")
public class AgentToolCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一次对话请求的关联 id；同一轮的所有工具调用共享，用于把多次调用聚合成一条链。 */
    private String traceId;

    /** 会话 id。 */
    private String sessionId;

    /** 用户 id（取自 /tool/user/info）；未登录为空。 */
    private Long userId;

    /** 用户名；未登录为空。 */
    private String username;

    /** 工具名：listWorkOrders / queryWorkOrder。 */
    private String toolName;

    /** 工具入参 JSON。 */
    private String toolArgs;

    /** 调用结果：SUCCESS / ERROR。 */
    private String callStatus;

    /** 项目一错误体的 code（失败时）；成功为空。 */
    private Integer resultCode;

    /** 返回体摘要（截断，不存全量）。 */
    private String resultExcerpt;

    /** 返回体原始长度（字符数）。 */
    private Integer resultLength;

    /** 调用耗时（毫秒）。 */
    private Long latencyMs;

    /** 异常信息（失败时）。 */
    private String errorMessage;

    /** 模型提供方：mock / openai。 */
    private String provider;

    /** 创建时间，由数据库默认值填充。 */
    private LocalDateTime createTime;
}
