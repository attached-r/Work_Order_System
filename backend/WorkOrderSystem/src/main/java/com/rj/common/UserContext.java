package com.rj.common;

/**
 * 当前登录用户上下文 基于ThreadLocal
 * <p> </p>
 * 登录拦截器校验后 通过写入 {@link UserAuth} 业务层可以随处读取 请求结束时必须清除
 * */
public class UserContext {

    private static final ThreadLocal<UserAuth> CURRENT_USER = new ThreadLocal<>();

    public static void set(UserAuth userAuth) {
        CURRENT_USER.set(userAuth);
    }

    /** 当前登录用户的鉴权快照,未登录时为 null */
    public static UserAuth get() {
        return CURRENT_USER.get();
    }

    /** 当前登录用户ID,未登录时为 null */
    public static Long getUserId() {
        UserAuth userAuth = CURRENT_USER.get();
        return userAuth == null ? null : userAuth.getUserId();
    }

    /** 当前登录用户所属部门ID,无部门(如 ADMIN)时为 null */
    public static Long getDepartmentId() {
        UserAuth userAuth = CURRENT_USER.get();
        return userAuth == null ? null : userAuth.getDepartmentId();
    }

    /** 当前登录用户是否拥有指定权限 */
    public static boolean hasPermission(String permCode) {
        UserAuth userAuth = CURRENT_USER.get();
        return userAuth != null && userAuth.hasAll(permCode);
    }

    /**
     * 清理当前线程中的用户信息
     * web 容器线程会复用 不清理会造成串号风险
     */
    public static void clear() {
        CURRENT_USER.remove();
    }
}
