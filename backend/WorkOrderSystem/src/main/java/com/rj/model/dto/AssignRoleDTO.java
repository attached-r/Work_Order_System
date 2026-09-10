package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 分配角色请求(覆盖式:以本次提交的列表为准)
 */
@Schema(description = "分配角色请求")
@Data
public class AssignRoleDTO {

    @Schema(description = "角色ID列表,至少一个")
    @NotEmpty(message = "请至少选择一个角色")
    private List<Long> roleIds;
}
