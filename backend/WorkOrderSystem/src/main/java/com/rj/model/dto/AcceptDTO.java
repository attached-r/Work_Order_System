package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单验收请求参数
 * <p>
 * 提单人对「3 待验收」工单作出决定:通过 → 4 已完成(终态);退回 → 2 处理中(返工)。
 * 通过/退回共用一个接口,由 approved 区分,退回时建议写明原因。
 */
@Data
@Schema(description = "工单验收请求参数")
public class AcceptDTO {

    @Schema(description = "是否通过:true 通过(→已完成) false 退回(→处理中)")
    @NotNull(message = "验收结论不能为空")
    private Boolean approved;

    @Schema(description = "验收意见/退回原因,可空")
    @Size(max = 500, message = "验收意见长度不能超过500个字符")
    private String remark;
}
