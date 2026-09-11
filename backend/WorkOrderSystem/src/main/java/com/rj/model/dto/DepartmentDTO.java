package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门新增/修改请求参数
 * <p>
 * 新增与修改共用;deptCode 为唯一业务标识,deptName 为展示名。
 */
@Data
@Schema(description = "部门新增/修改请求参数")
public class DepartmentDTO {

    @Schema(description = "部门编码(唯一,代码判断用)")
    @NotBlank(message = "部门编码不能为空")
    @Size(max = 20, message = "部门编码长度不能超过20个字符")
    private String deptCode;

    @Schema(description = "部门名称")
    @NotBlank(message = "部门名称不能为空")
    @Size(max = 50, message = "部门名称长度不能超过50个字符")
    private String deptName;

    @Schema(description = "备注,可空")
    @Size(max = 200, message = "备注长度不能超过200个字符")
    private String remark;
}
