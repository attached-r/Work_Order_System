package com.rj.common;

/**
 * Redis key 与过期时间常量
 */
public final class RedisConstants {

    private RedisConstants() {
    }

    /**
     * 登录 token 存储 key 前缀,完整 key: login:user:token:{userId}
     * value 为签发时的 JWT。登出删除、重新登录覆盖。
     */
    public static final String LOGIN_USER_TOKEN_KEY = "login:user:token:";

    /**
     * 登录态存活小时数,与 application.yaml 中 jwt.timeout(秒) 保持换算一致:
     * jwt.timeout = 7200s = 2h。登录与拦截器滑动续期都用该值。
     */
    public static final long LOGIN_TOKEN_EXPIRE_HOURS = 2L;
}
