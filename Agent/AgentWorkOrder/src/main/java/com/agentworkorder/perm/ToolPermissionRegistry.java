package com.agentworkorder.perm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 「工具名 → 所需权限码集合」的静态注册表（agent 侧的可见性闸门依据）。
 *
 * <p>M4 把值从单个权限码升级为集合，语义是「用户持有集合中<b>任意一个</b>权限即可见」：
 * 六个写动作各要求自己的权限码；{@code listAssignableHandlers} 则要求
 * {@code workorder:dispatch} 或 {@code workorder:transfer} 任一即可。
 *
 * <p>映射关系不发明，全部对齐项目一 Tool 接口内的判权。工具名必须与 {@code @Tool(name=...)}
 * 逐字一致，否则查不到权限 → 被当成「未注册工具」而隐藏（fail-closed）。
 */
@Component
public class ToolPermissionRegistry {

    /** 工具名 → 权限码集合；任一命中即可见。查不到 = 未注册 = 不可见。 */
    private static final Map<String, Set<String>> TOOL_PERMS = Map.ofEntries(
            // 只读工具
            Map.entry("listWorkOrders", Set.of("workorder:query")),
            Map.entry("queryWorkOrder", Set.of("workorder:query")),
            Map.entry("searchKnowledge", Set.of("workorder:query")),
            // 六个写动作：每个动作独立权限码
            Map.entry("reviewWorkOrder", Set.of("workorder:review")),
            Map.entry("dispatchWorkOrder", Set.of("workorder:dispatch")),
            Map.entry("transferWorkOrder", Set.of("workorder:transfer")),
            Map.entry("processWorkOrder", Set.of("workorder:process")),
            Map.entry("acceptWorkOrder", Set.of("workorder:accept")),
            Map.entry("withdrawWorkOrder", Set.of("workorder:withdraw")),
            // 候选处理人：派单或转派任一权限即可见
            Map.entry("listAssignableHandlers", Set.of("workorder:dispatch", "workorder:transfer"))
    );

    /** 返回该工具所需的权限码集合；未注册的工具返回 {@code null}（调用方须视为「不可见」）。 */
    public Set<String> requiredPerms(String toolName) {
        return TOOL_PERMS.get(toolName);
    }

    /** 判断某用户的权限是否足以看到该工具：集合非空且任一命中。未注册一律不可见（fail-closed）。 */
    public boolean visibleTo(String toolName, UserPerms perms) {
        Set<String> required = requiredPerms(toolName);
        return required != null && perms != null && perms.hasAny(required);
    }
}
