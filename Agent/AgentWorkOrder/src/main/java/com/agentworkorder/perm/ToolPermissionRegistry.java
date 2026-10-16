package com.agentworkorder.perm;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 「工具名 → 所需权限码」的静态注册表（agent 侧的可见性闸门依据）。
 *
 * <p>映射关系不发明，全部对齐项目一 {@code ToolWorkOrderController} 上 {@code @RequiresPermission}
 * 已有的判定：只读工具都要求 {@code workorder:query}。工具名必须与 {@code @Tool(name=...)} 逐字一致，
 * 否则查不到权限码 → 会被 {@code PermService} 侧当成「未注册工具」而误隐藏。
 *
 * <p>M4 接入写工具时在此追加。注意写工具的「任一动作即可」语义在项目一那条
 * {@code @RequiresPermission}（AND 语义）里表达不了，必须在 agent 侧按动作拆开分别要求。
 */
@Component
public class ToolPermissionRegistry {

    /** 工具名 → 权限码。查不到时的语义是「未注册」，调用方应按 fail-closed 处理。 */
    private static final Map<String, String> TOOL_PERM = Map.of(
            "listWorkOrders", "workorder:query",
            "queryWorkOrder", "workorder:query"
    );

    /** 返回该工具所需的权限码；未注册的工具返回 {@code null}（调用方须视为「不可见」）。 */
    public String requiredPerm(String toolName) {
        return TOOL_PERM.get(toolName);
    }

    /** 判断某用户的权限是否足以看到该工具。未注册的工具一律视为不可见（fail-closed）。 */
    public boolean visibleTo(String toolName, UserPerms perms) {
        String required = requiredPerm(toolName);
        return required != null && perms != null && perms.has(required);
    }
}
