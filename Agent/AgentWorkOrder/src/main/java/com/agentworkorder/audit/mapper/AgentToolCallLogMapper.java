package com.agentworkorder.audit.mapper;

import com.agentworkorder.audit.AgentToolCallLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 审计日志 Mapper。
 *
 * <p>审计只写不读，故无需自定义查询；{@link BaseMapper} 提供的 {@code insert} 已足够。
 * M5 增加一个「按时间批量删除」，供 {@code AuditRetentionJob} 使用。
 */
public interface AgentToolCallLogMapper extends BaseMapper<AgentToolCallLog> {

    /**
     * 删除早于 cutoff 的审计，最多 limit 行。
     *
     * <p>MySQL 支持 {@code DELETE ... LIMIT}；分批删避免长事务、大 binlog 和锁表。
     */
    @Delete("DELETE FROM agent_tool_call_log WHERE create_time < #{cutoff} LIMIT #{limit}")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
