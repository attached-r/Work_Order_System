package com.rj.controller;

import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.dto.AssignDepartmentDTO;
import com.rj.model.dto.AssignRoleDTO;
import com.rj.model.dto.LoginDTO;
import com.rj.model.dto.RegisterDTO;
import com.rj.model.vo.UserVO;
import com.rj.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="用户管理")
@RequestMapping("/user")
@RestController
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    /**
     * 登录成功后返回 token。
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginDTO loginDTO) {
        return userService.login(loginDTO.getUsername(), loginDTO.getPassword());
    }

    /**
     * 注册成功后仅返回成功结果,由前端引导用户去登录。
     * 新账号不带角色,需管理员通过 /user/{userId}/roles 分配。
     */
    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO registerDTO) {
        return userService.register(registerDTO);
    }

    /**
     * 当前登录用户信息:含角色与权限码,前端据此控制按钮显隐。
     */
    @Operation(summary = "当前登录用户信息")
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.success(userService.me());
    }

    /**
     * 用户分页列表,用于管理端选人。
     */
    @Operation(summary = "用户分页列表")
    @RequiresPermission("user:manage")
    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           @RequestParam(required = false) String keyword) {
        return Result.success(userService.pageUsers(current, size, keyword));
    }

    /**
     * 覆盖式分配角色:以本次提交的角色列表为准。
     */
    @Operation(summary = "分配角色")
    @RequiresPermission("user:manage")
    @PutMapping("/{userId}/roles")
    public Result<Void> assignRoles(@PathVariable Long userId, @Valid @RequestBody AssignRoleDTO assignRoleDTO) {
        userService.assignRoles(userId, assignRoleDTO.getRoleIds());
        return Result.success();
    }

    /**
     * 调整用户所属部门,决定其工单的数据范围。
     */
    @Operation(summary = "调整所属部门")
    @RequiresPermission("user:manage")
    @PutMapping("/{userId}/department")
    public Result<Void> assignDepartment(@PathVariable Long userId,
                                         @Valid @RequestBody AssignDepartmentDTO assignDepartmentDTO) {
        userService.assignDepartment(userId, assignDepartmentDTO.getDepartmentId());
        return Result.success();
    }
}
