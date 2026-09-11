package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改账号状态请求
 * <p>
 * 只暴露启停用所必需的 status 字段;取值 0/1 由 {@link Min}/{@link Max} 收口,
 * 避免传入越界值直接落库。
 */
@Data
@Schema(description = "修改账号状态请求")
public class UpdateUserStatusDTO {

    @Schema(description = "账号状态:0禁用 1启用")
    @NotNull(message = "账号状态不能为空")
    @Min(value = 0, message = "账号状态只能为0或1")
    @Max(value = 1, message = "账号状态只能为0或1")
    private Integer status;
}
