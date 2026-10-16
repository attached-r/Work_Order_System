package com.agentworkorder.perm;

import java.util.Set;

/**
 * 一个用户的身份与权限快照（来自项目一 {@code /tool/user/info} 的 {@code userId/username/perms}）。
 *
 * <p>它是「agent 不发明权限」这条纪律的载体：这里的所有字段都<b>原样抄自项目一</b>，
 * agent 不对它们做任何推导/合并/猜测。拿不到清单时一律降级为 {@link #ANONYMOUS}（空权限，fail-closed）。
 *
 * @param userId    用户 id，可空（匿名）
 * @param username  用户名，可空
 * @param permCodes 权限码集合（如 {@code workorder:query}），永不为 {@code null}
 */
public record UserPerms(Long userId, String username, Set<String> permCodes) {

    /** 匿名（未登录 / token 失效 / 项目一不可达）——空权限，等价于「什么工具都看不到」。 */
    public static final UserPerms ANONYMOUS = new UserPerms(null, null, Set.of());

    /** 该用户是否持有某权限码。 */
    public boolean has(String permCode) {
        return permCode != null && permCodes.contains(permCode);
    }
}
