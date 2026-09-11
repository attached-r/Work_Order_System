package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单审核请求参数
 * <p>
 * 审核人对待审核(0)工单作出决定:通过 → 1 待派单;驳回 → 5 已驳回。
 * 通过/驳回共用一个接口,由 approved 区分,驳回时建议在 remark 里写明原因。
 */
@Data
@Schema(description = "工单审核请求参数")
public class ReviewDTO {

    @Schema(description = "是否通过:true 通过(→待派单) false 驳回(→已驳回)")
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    @Schema(description = "审核意见/驳回原因,可空")
    @Size(max = 500, message = "审核意见长度不能超过500个字符")
    private String remark;
}
