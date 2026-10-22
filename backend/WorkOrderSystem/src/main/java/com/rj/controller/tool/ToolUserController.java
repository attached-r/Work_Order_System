package com.rj.controller.tool;

import com.rj.model.vo.UserBriefVO;
import com.rj.model.vo.UserVO;
import com.rj.service.IToolUserService;
import com.rj.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent Tool 用户接口(供项目二 OpenFeign 调用),对应设计文档 §2.5。
 * <p>
 * 返回体同工单工具体:成功直接返回 {@link UserVO}(含角色与权限码),失败走精简错误体。
 */
@Tag(name = "Agent Tool - 用户")
@RequestMapping("/tool/user")
@RestController
@RequiredArgsConstructor
public class ToolUserController {

    private final IUserService userService;
    private final IToolUserService toolUserService;

    /**
     * 当前用户信息 + 角色 + 权限码。
     * <p>
     * <b>刻意不挂 {@code @RequiresPermission}:</b>登录即可,与既有 {@code GET /user/me} 一致。
     * Agent 可以在发起工具调用前先取这里的 perms,过滤掉用户无权限的工具(设计文档 §3.4 模块4),
     * 从源头挡住大模型幻觉诱导的越权调用。
     */
    @Operation(summary = "当前用户信息(Agent)")
    @GetMapping("/info")
    public UserVO info() {
        return userService.me();
    }

    /**
     * 可指派处理人候选(派单/转派用)。
     * <p>
     * 与 {@code /user/directory} 的区别:走 tool 裸数据契约、按部门收敛、只看启用账号且
     * 角色为 HANDLER。调用者需具备 {@code workorder:dispatch} 或 {@code workorder:transfer};
     * 非 ADMIN 传了 {@code departmentId} 也会被忽略,强制取本部门。
     */
    @Operation(summary = "可指派处理人候选(Agent)")
    @GetMapping("/handlers")
    public List<UserBriefVO> handlers(@RequestParam(required = false) Long departmentId,
                                      @RequestParam(required = false) String keyword) {
        return toolUserService.listHandlers(departmentId, keyword);
    }
}
