package com.agentworkorder.audit.mapper;

import com.agentworkorder.audit.AgentToolCallLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 审计日志 Mapper。
 *
 * <p>审计只写不读，故无需自定义方法——{@link BaseMapper} 提供的 {@code insert} 已足够。
 * 由启动类 {@code @MapperScan("com.agentworkorder.audit.mapper")} 注册。
 */
public interface AgentToolCallLogMapper extends BaseMapper<AgentToolCallLog> {
}
