package com.rj.controller;

import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.dto.AssignPermissionDTO;
import com.rj.model.pojo.Role;
import com.rj.service.IRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "角色管理")
@RequestMapping("/role")
@RestController
@RequiredArgsConstructor
public class RoleController {

    private final IRoleService roleService;

    /**
     * 角色下拉选项,分配角色时用。
     */
    @Operation(summary = "角色列表")
    @RequiresPermission("user:manage")
    @GetMapping("/list")
    public Result<List<Role>> list() {
        return Result.success(roleService.listAll());
    }

    // 新增角色 由于角色基本上固定的 不需要修改

    /**
     * 覆盖式分配角色权限:以本次提交的权限列表为准,空列表表示回收全部权限。
     */
    @Operation(summary = "分配权限")
    @RequiresPermission("user:manage")
    @PutMapping("/{roleId}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long roleId,
                                          @Valid @RequestBody AssignPermissionDTO assignPermissionDTO) {
        roleService.assignPermissions(roleId, assignPermissionDTO.getPermissionIds());
        return Result.success();
    }
}
