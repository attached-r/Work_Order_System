package com.rj.controller;

import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.pojo.Role;
import com.rj.service.IRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
