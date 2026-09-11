package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 分配权限请求(覆盖式:以本次提交的列表为准)
 * <p>
 * 允许空列表,表示回收该角色的全部权限,因此用 {@link NotNull} 而非 {@link jakarta.validation.constraints.NotEmpty}:
 * 前端传 [] 是合法语义,null 才是漏传。
 */
@Data
@Schema(description = "分配权限请求")
public class AssignPermissionDTO {

    @Schema(description = "权限ID列表,可为空列表表示清空")
    @NotNull(message = "权限ID列表不能为null,清空请传空数组")
    private List<Long> permissionIds;
}
