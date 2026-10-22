-- =============================================================================
-- service-agent（agent 侧）数据库脚本 —— M2 审计表
-- 定位：这是 agent 唯一自己持有的数据表，只存「工具调用流水」这类非业务数据；
-- =============================================================================


-- Agent 侧审计表：只增不改，无逻辑删除、无 update_time
DROP TABLE IF EXISTS `agent_tool_call_log`;
CREATE TABLE `agent_tool_call_log` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id`       VARCHAR(64)   NOT NULL                COMMENT '一次对话请求的关联id(agent生成,同一轮的所有工具调用共享)',
  `session_id`     VARCHAR(64)   NOT NULL                COMMENT '会话id',
  `user_id`        BIGINT        NULL                    COMMENT '用户id(取自 /tool/user/info)',
  `username`       VARCHAR(64)   NULL                    COMMENT '用户名',
  `tool_name`      VARCHAR(64)   NOT NULL                COMMENT '工具名:listWorkOrders/queryWorkOrder/searchKnowledge/reviewWorkOrder/dispatchWorkOrder/transferWorkOrder/processWorkOrder/acceptWorkOrder/withdrawWorkOrder/listAssignableHandlers',
  `tool_args`      VARCHAR(1000) NULL                    COMMENT '工具入参JSON',
  `call_status`    VARCHAR(16)   NOT NULL                COMMENT '调用结果:SUCCESS / ERROR',
  `result_code`    INT           NULL                    COMMENT '项目一错误体的code(失败时);成功为NULL',
  `result_excerpt` VARCHAR(512)  NULL                    COMMENT '返回体摘要(截断,不存全量)',
  `result_length`  INT           NULL                    COMMENT '返回体原始长度(字节)',
  `latency_ms`     BIGINT        NULL                    COMMENT '调用耗时(毫秒)',
  `error_message`  VARCHAR(512)  NULL                    COMMENT '异常信息(失败时)',
  `provider`       VARCHAR(32)   NULL                    COMMENT '模型提供方:mock / openai',
  `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_session`      (`session_id`),
  KEY `idx_user_time`    (`user_id`, `create_time`),
  KEY `idx_tool_time`    (`tool_name`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT='Agent 工具调用审计日志(只增不改)';
