package com.rj.common;

/**
 * 当前登录用户上下文 基于ThreadLocal
 * <p> </p>
 * 登录拦截器校验后 通过写入userId 业务层可以随处读取 请求结束时必须清除
 * */
public class UserContext {
    // 用户Id
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static  Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 清理当前线程中的用户信息
     * web 容器线程会复用 不清理会造成串号风险
     */
    public  static void clear() {
        CURRENT_USER_ID.remove();
    }
}
