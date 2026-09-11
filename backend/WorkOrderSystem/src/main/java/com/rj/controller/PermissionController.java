package com.rj.controller;

import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.vo.PermissionVO;
import com.rj.service.IPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "权限管理")
@RequestMapping("/permission")
@RestController
@RequiredArgsConstructor
public class PermissionController {

    private final IPermissionService permissionService;

    /**
     * 权限树,供角色分配权限时勾选。
     */
    @Operation(summary = "权限树")
    @RequiresPermission("user:manage")
    @GetMapping("/tree")
    public Result<List<PermissionVO>> tree() {
        return Result.success(permissionService.tree());
    }
}
