package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 派单 / 转派请求参数
 * <p>
 * 派单:待派单(1) → 处理中(2),指定处理人;
 * 转派:处理中(2) → 处理中(2),更换处理人。
 * 两者入参一致,由后端按工单当前状态区分。
 */
@Data
@Schema(description = "派单/转派请求参数")
public class DispatchDTO {

    @Schema(description = "目标处理人用户ID")
    @NotNull(message = "处理人不能为空")
    @Positive(message = "处理人ID必须为正数")
    private Long handlerId;

    @Schema(description = "派单/转派说明,可空")
    @Size(max = 500, message = "说明长度不能超过500个字符")
    private String remark;
}
