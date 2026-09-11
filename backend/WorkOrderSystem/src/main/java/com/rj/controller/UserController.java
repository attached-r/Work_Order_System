package com.rj.controller;

import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.common.annotation.RequiresPermission;
import com.rj.model.dto.AssignDepartmentDTO;
import com.rj.model.dto.AssignRoleDTO;
import com.rj.model.dto.LoginDTO;
import com.rj.model.dto.RegisterDTO;
import com.rj.model.dto.ResetPasswordDTO;
import com.rj.model.dto.UpdateUserDTO;
import com.rj.model.dto.UpdateUserStatusDTO;
import com.rj.model.vo.UserVO;
import com.rj.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * 登出:删除当前登录用户的 token 与鉴权快照,使其立即失效。
     */
    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.success();
    }

    /**
     * 启停用账号:0禁用 1启用;禁用会让该账号的登录态立即失效。
     */
    @Operation(summary = "启停用账号")
    @RequiresPermission("user:manage")
    @PutMapping("/{userId}/status")
    public Result<Void> updateStatus(@PathVariable Long userId,
                                     @Valid @RequestBody UpdateUserStatusDTO updateUserStatusDTO) {
        userService.updateStatus(userId, updateUserStatusDTO.getStatus());
        return Result.success();
    }

    /**
     * 重置指定用户密码(管理员操作,无需原密码);重置后该用户需重新登录。
     */
    @Operation(summary = "重置密码")
    @RequiresPermission("user:manage")
    @PutMapping("/{userId}/password")
    public Result<Void> resetPassword(@PathVariable Long userId,
                                      @Valid @RequestBody ResetPasswordDTO resetPasswordDTO) {
        userService.resetPassword(userId, resetPasswordDTO.getNewPassword());
        return Result.success();
    }

    /**
     * 修改用户基本信息(姓名/电话/部门);账号与密码不走此接口。
     */
    @Operation(summary = "修改用户信息")
    @RequiresPermission("user:manage")
    @PutMapping("/{userId}")
    public Result<Void> updateUser(@PathVariable Long userId,
                                   @Valid @RequestBody UpdateUserDTO updateUserDTO) {
        userService.updateUser(userId, updateUserDTO);
        return Result.success();
    }

    /**
     * 删除用户(逻辑删除),同时清理其角色关联。
     */
    @Operation(summary = "删除用户")
    @RequiresPermission("user:manage")
    @DeleteMapping("/{userId}")
    public Result<Void> deleteUser(@PathVariable Long userId) {
        userService.deleteUser(userId);
        return Result.success();
    }
}
