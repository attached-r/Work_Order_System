package com.agentworkorder.perm;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 工具可见性注册表测试（P1）。
 *
 * <p>这是 M2 建立的“第二层权限门”：用户没有权限的工具，模型<b>根本看不到</b>。
 * 本测试不启动 Spring，直接验证“权限集合 → 工具可见性”的映射，
 * 重点锁住 fail-closed（未注册 / 无权限 → 不可见）。
 */
class ToolPermissionRegistryTest {

    private final ToolPermissionRegistry registry = new ToolPermissionRegistry();

    @Test
    void anonymousShouldSeeNoTool() {
        for (String tool : allToolNames()) {
            assertThat(registry.visibleTo(tool, UserPerms.ANONYMOUS))
                    .as("匿名用户不应看到工具 %s", tool)
                    .isFalse();
        }
    }

    @Test
    void queryPermissionShouldExposeReadTools() {
        UserPerms query = perms("workorder:query");

        assertThat(registry.visibleTo("listWorkOrders", query)).isTrue();
        assertThat(registry.visibleTo("queryWorkOrder", query)).isTrue();
        assertThat(registry.visibleTo("searchKnowledge", query)).isTrue();

        // 读权限不能越界到写工具
        assertThat(registry.visibleTo("reviewWorkOrder", query)).isFalse();
        assertThat(registry.visibleTo("dispatchWorkOrder", query)).isFalse();
        assertThat(registry.visibleTo("listAssignableHandlers", query)).isFalse();
    }

    @Test
    void eachWriteToolShouldRequireItsOwnPermission() {
        assertThat(registry.visibleTo("reviewWorkOrder", perms("workorder:review"))).isTrue();
        assertThat(registry.visibleTo("dispatchWorkOrder", perms("workorder:dispatch"))).isTrue();
        assertThat(registry.visibleTo("transferWorkOrder", perms("workorder:transfer"))).isTrue();
        assertThat(registry.visibleTo("processWorkOrder", perms("workorder:process"))).isTrue();
        assertThat(registry.visibleTo("acceptWorkOrder", perms("workorder:accept"))).isTrue();
        assertThat(registry.visibleTo("withdrawWorkOrder", perms("workorder:withdraw"))).isTrue();

        // 审核权不能派单
        assertThat(registry.visibleTo("dispatchWorkOrder", perms("workorder:review"))).isFalse();
    }

    @Test
    void listAssignableHandlersShouldUseOrSemantics() {
        // 派单或转派任一权限即可见；这是 M4 注册表从单码升级为集合的原因
        assertThat(registry.visibleTo("listAssignableHandlers", perms("workorder:dispatch"))).isTrue();
        assertThat(registry.visibleTo("listAssignableHandlers", perms("workorder:transfer"))).isTrue();
        assertThat(registry.visibleTo("listAssignableHandlers",
                perms("workorder:dispatch", "workorder:transfer"))).isTrue();

        assertThat(registry.visibleTo("listAssignableHandlers", perms("workorder:query"))).isFalse();
        assertThat(registry.visibleTo("listAssignableHandlers", UserPerms.ANONYMOUS)).isFalse();
    }

    @Test
    void unknownToolShouldBeDeniedByDefault() {
        assertThat(registry.visibleTo("notRegisteredTool", perms("workorder:query"))).isFalse();
        assertThat(registry.requiredPerms("notRegisteredTool")).isNull();
    }

    @Test
    void userPermsHasAnyShouldWorkForOrSemantics() {
        UserPerms perms = perms("workorder:dispatch");

        assertThat(perms.hasAny(Set.of("workorder:dispatch", "workorder:transfer"))).isTrue();
        assertThat(perms.hasAny(Set.of("workorder:review", "workorder:accept"))).isFalse();
        assertThat(perms.hasAny(Set.of())).isFalse();
        assertThat(perms.hasAny(null)).isFalse();
    }

    private static UserPerms perms(String... codes) {
        return new UserPerms(1L, "tester", Set.of(codes));
    }

    private static Set<String> allToolNames() {
        return Set.of(
                "listWorkOrders", "queryWorkOrder", "searchKnowledge",
                "reviewWorkOrder", "dispatchWorkOrder", "transferWorkOrder",
                "processWorkOrder", "acceptWorkOrder", "withdrawWorkOrder",
                "listAssignableHandlers"
        );
    }
}
