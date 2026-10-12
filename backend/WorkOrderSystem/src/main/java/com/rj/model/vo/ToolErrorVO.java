package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tool 接口精简错误体:{@code {"code":..,"message":..}}。
 * <p>
 * 与全站统一响应体 {@link com.rj.common.Result} 的区别:去掉 data/timestamp 包装,
 * 只保留 Agent(大模型)判断成败必需的两个字段,契合设计文档「返回简单干净 JSON」的约束。
 * <p>
 * 成功响应不套本类——直接返回业务对象(如 {@code WorkOrderVO});本类只用于错误路径,
 * 由 {@link com.rj.exception.ToolExceptionHandler} 统一产出。
 *
 * @param code    业务状态码,取自 {@link com.rj.common.ResultCode}
 * @param message 可读的失败说明,会作为工具返回值交给大模型整理
 */
@Schema(description = "Tool 接口错误响应")
public record ToolErrorVO(Integer code, String message) {
}
