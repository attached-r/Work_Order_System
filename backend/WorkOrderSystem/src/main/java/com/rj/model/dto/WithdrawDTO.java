package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单撤回 / 取消请求参数
 * <p>
 * 提单人取消自己尚未完成的工单(待审核/待派单/处理中/已驳回 → 已取消,终态)。
 * 已进入「待验收」及以后的工单不允许撤回,由状态机拦截。
 */
@Data
@Schema(description = "工单撤回/取消请求参数")
public class WithdrawDTO {

    @Schema(description = "撤回/取消原因,可空")
    @Size(max = 500, message = "撤回原因长度不能超过500个字符")
    private String remark;
}
