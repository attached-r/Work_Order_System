package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent 变更工单状态请求参数(演示)
 * <p>
 * <b>为什么要一个「通用」入参:</b>真实的状态流转不是「改一个 status 字段」,而是
 * 6 类各自带守卫的动作——审核(通过/驳回)、派单/转派、处理完成、验收(通过/退回)、
 * 撤回/取消,每类需要的参数不同。本 DTO 取这些动作入参的<b>并集</b>
 * ({@code targetStatus + handlerId + approved + remark}),因此将来接入真实状态机时
 * <b>无需再改 DTO</b>,可直接按 {@code (当前态, 目标态)} 分派到既有 Service 方法。
 * <p>
 * 本次为 demo:仅解析与回显,不落库。
 */
@Data
@Schema(description = "Agent 变更工单状态请求(演示)")
public class ToolWorkOrderStatusDTO {

    @Schema(description = "目标状态:0待审核 1待派单 2处理中 3待验收 4已完成 5已驳回 6已取消 7已超时")
    @NotNull(message = "目标状态不能为空")
    @Min(value = 0, message = "目标状态非法")
    @Max(value = 7, message = "目标状态非法")
    private Integer targetStatus;

    @Schema(description = "目标处理人ID,派单/转派用;其余动作留空")
    private Long handlerId;

    @Schema(description = "审核/验收结论:true 通过 false 驳回/退回;其余动作留空")
    private Boolean approved;

    @Schema(description = "备注/驳回原因,可空")
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;
}
