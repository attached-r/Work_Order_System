package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent 工单动作请求参数(M4 写接口契约)。
 * <p>
 * 六个动作的参数取并集:{@code action + expectedFromStatus + handlerId + approved + remark}。
 * 字段在本类放开校验(可空),因为不同动作只用其中一部分——必填性由 Service 按动作判定,
 * 这样错误提示能说明「哪个动作缺哪个参数」,而不是笼统的「字段不能为空」。
 */
@Data
@Schema(description = "Agent 工单动作请求")
public class ToolWorkOrderActionDTO {

    @NotBlank(message = "动作不能为空")
    @Schema(description = "动作:REVIEW/DISPATCH/TRANSFER/PROCESS/ACCEPT/WITHDRAW", example = "REVIEW")
    private String action;

    @NotNull(message = "期望当前状态不能为空")
    @Min(value = 0, message = "状态码非法")
    @Max(value = 7, message = "状态码非法")
    @Schema(description = "调用方预览时看到的当前状态码;与最新状态不一致时拒绝执行(防状态漂移)", example = "0")
    private Integer expectedFromStatus;

    @Positive(message = "处理人ID必须为正数")
    @Schema(description = "目标处理人ID,派单/转派必填")
    private Long handlerId;

    @Schema(description = "审核/验收结论:true 通过 false 驳回/退回;审核与验收必填")
    private Boolean approved;

    @Size(max = 500, message = "备注长度不能超过500个字符")
    @Schema(description = "备注/驳回原因,可空")
    private String remark;
}
