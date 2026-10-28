package com.rj.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权快照 {@link UserAuth} 的单元测试（P1）。
 *
 * <p>项目一所有接口级权限都最终落到 {@code userAuth.hasAll(required)}：
 * 注解上的多权限是 AND 语义，所以这里要特别锁住“缺一个就不通过”。
 * 这不是在重复实现权限框架，而是在保护“AND 语义不能被悄悄改成 OR”。
 */
class UserAuthTest {

    private static final UserAuth AUTH = new UserAuth(
            1L,
            10L,
            Set.of("REVIEWER", "DISPATCHER"),
            Set.of("workorder:query", "workorder:review")
    );

    @Test
    void noRequiredPermissionsShouldAlwaysPass() {
        assertThat(AUTH.hasAll()).isTrue();
        assertThat(AUTH.hasAll(new String[0])).isTrue();
        assertThat(AUTH.hasAll((String[]) null)).isTrue();
    }

    @Test
    void singlePermissionShouldMatchExactly() {
        assertThat(AUTH.hasAll("workorder:query")).isTrue();
        assertThat(AUTH.hasAll("workorder:review")).isTrue();
        assertThat(AUTH.hasAll("workorder:dispatch")).isFalse();
    }

    @Test
    void multiplePermissionsShouldUseAndSemantics() {
        // 两个都有 → 通过
        assertThat(AUTH.hasAll("workorder:query", "workorder:review")).isTrue();

        // 缺一个 → 不通过；这是 @RequiresPermission("a","b") 的真实语义
        assertThat(AUTH.hasAll("workorder:query", "workorder:dispatch")).isFalse();
        assertThat(AUTH.hasAll("workorder:review", "workorder:dispatch")).isFalse();
    }

    @Test
    void nullPermissionSetShouldFailClosed() {
        UserAuth noPerms = new UserAuth(2L, 10L, Set.of("SUBMITTER"), null);

        assertThat(noPerms.hasAll("workorder:query")).isFalse();
        assertThat(noPerms.hasAll()).isTrue();
    }
}
