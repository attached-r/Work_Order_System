package com.rj.service.impl;

import com.rj.common.ResultCode;
import com.rj.common.UserAuth;
import com.rj.common.UserContext;
import com.rj.exception.BusinessException;
import com.rj.model.vo.UserBriefVO;
import com.rj.service.IToolUserService;
import com.rj.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent Tool 用户服务实现:复用 {@link IUserService#listDirectory()} 做内存裁剪。
 * <p>
 * 用户量级可控,不值得为这个只读场景新增部门级查询;等用户规模上来再下推到 SQL。
 */
@Service
@RequiredArgsConstructor
public class ToolUserService implements IToolUserService {

    /** 角色码:管理员可跨部门查询 */
    private static final String ROLE_ADMIN = "ADMIN";
    /** 角色码:只有处理人才能被指派 */
    private static final String ROLE_HANDLER = "HANDLER";
    /** 权限码:调用候选接口要求二者之一(OR 语义) */
    private static final String PERM_DISPATCH = "workorder:dispatch";
    private static final String PERM_TRANSFER = "workorder:transfer";
    /** 用户状态:1 启用 */
    private static final int STATUS_ENABLED = 1;

    private final IUserService userService;

    @Override
    public List<UserBriefVO> listHandlers(Long departmentId, String keyword) {
        // 第 1 步:判权。只有派单/转派的人需要这份名单,任一权限即可
        if (!UserContext.hasPermission(PERM_DISPATCH) && !UserContext.hasPermission(PERM_TRANSFER)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "缺少派单/转派权限");
        }

        // 第 2 步:确定部门范围。非管理员只能看本部门,且忽略请求参数,防止跨部门拉人
        boolean admin = isAdmin();
        Long scope = admin ? departmentId : UserContext.getDepartmentId();
        if (!admin && scope == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前用户未归属部门,无法查询处理人");
        }

        // 第 3 步:裁剪。账号/姓名关键字 → 启用账号 → HANDLER 角色 → 部门范围
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase();
        return userService.listDirectory().stream()
                .filter(user -> normalized.isEmpty() || matchesKeyword(user, normalized))
                .filter(user -> Integer.valueOf(STATUS_ENABLED).equals(user.getStatus()))
                .filter(user -> user.getRoles() != null && user.getRoles().contains(ROLE_HANDLER))
                .filter(user -> scope == null || scope.equals(user.getDepartmentId()))
                .toList();
    }

    /** 账号或真实姓名包含关键字即命中(忽略大小写) */
    private static boolean matchesKeyword(UserBriefVO user, String normalizedKeyword) {
        return containsIgnoreCase(user.getUsername(), normalizedKeyword)
                || containsIgnoreCase(user.getRealName(), normalizedKeyword);
    }

    private static boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase().contains(normalizedKeyword);
    }

    /** 当前用户是否为管理员(角色码来自项目一登录快照,不另行推导) */
    private static boolean isAdmin() {
        UserAuth auth = UserContext.get();
        return auth != null && auth.getRoleCodes() != null && auth.getRoleCodes().contains(ROLE_ADMIN);
    }
}
