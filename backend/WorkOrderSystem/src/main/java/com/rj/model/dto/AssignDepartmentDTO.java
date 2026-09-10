package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 调整所属部门请求
 */
@Schema(description = "调整所属部门请求")
@Data
public class AssignDepartmentDTO {

    @Schema(description = "部门ID")
    @NotNull(message = "部门ID不能为空")
    private Long departmentId;
}
