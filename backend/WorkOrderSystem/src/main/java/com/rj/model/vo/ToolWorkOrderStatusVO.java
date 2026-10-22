package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Agent 工单动作执行结果(M4 写接口响应)。
 * <p>
 * 成功即为已落库:{@code changed} 恒为 {@code true},不再有「解析了但没写」的成功态。
 * 不回读完整详情——目标状态是确定的;模型若需要最新详情,应再调一次工单详情工具。
 */
@Data
@Schema(description = "Agent 工单动作执行结果")
public class ToolWorkOrderStatusVO {

    @Schema(description = "工单ID")
    private Long id;

    @Schema(description = "工单编号")
    private String orderNo;

    @Schema(description = "执行的动作:REVIEW/DISPATCH/TRANSFER/PROCESS/ACCEPT/WITHDRAW")
    private String action;

    @Schema(description = "变更前状态码")
    private Integer fromStatus;

    @Schema(description = "变更前状态名")
    private String fromStatusDesc;

    @Schema(description = "变更后状态码")
    private Integer targetStatus;

    @Schema(description = "变更后状态名")
    private String targetStatusDesc;

    @Schema(description = "本次是否真的改动了工单:成功恒为 true")
    private boolean changed;

    @Schema(description = "人读说明")
    private String message;
}
