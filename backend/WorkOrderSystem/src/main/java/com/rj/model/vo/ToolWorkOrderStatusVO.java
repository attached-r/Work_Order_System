package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Agent 变更工单状态响应(演示)
 * <p>
 * 演示实现不改库,因此额外用 {@code changed=false} 与 {@code message} 显式告诉调用方
 * 「约定已收到、动作已解析,但未真正落库」,避免 Agent 把它当成已生效的写操作。
 *
 * @see com.rj.service.impl.ToolWorkOrderService#changeStatus
 */
@Data
@Schema(description = "Agent 变更工单状态响应(演示)")
public class ToolWorkOrderStatusVO {

    @Schema(description = "工单ID")
    private Long id;

    @Schema(description = "变更前状态码")
    private Integer fromStatus;

    @Schema(description = "变更前状态名")
    private String fromStatusDesc;

    @Schema(description = "请求的目标状态码")
    private Integer targetStatus;

    @Schema(description = "目标状态名")
    private String targetStatusDesc;

    @Schema(description = "解析出的动作:review/dispatch/transfer/process/accept/withdraw/unknown")
    private String action;

    @Schema(description = "该流转在状态机上是否结构合法(只读判断,不代表已执行)")
    private boolean transitionLegal;

    @Schema(description = "本次是否真的改动了工单:演示实现恒为 false")
    private boolean changed;

    @Schema(description = "人读说明")
    private String message;
}
