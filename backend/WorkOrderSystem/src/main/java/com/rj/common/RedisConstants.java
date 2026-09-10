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
     * 用户鉴权快照缓存 key 前缀,完整 key: login:user:auth:{userId}
     * value 为 {@link UserAuth} 的 JSON(部门ID + 角色码 + 权限码)。
     * 与 token 同生命周期;管理员修改用户角色或部门后删除该 key,
     * 下次请求回源重建,保证变更立即生效。
     */
    public static final String LOGIN_USER_AUTH_KEY = "login:user:auth:";

    /**
     * 登录态存活小时数,与 application.yaml 中 jwt.timeout(秒) 保持换算一致:
     * jwt.timeout = 7200s = 2h。登录与拦截器滑动续期都用该值。
     */
    public static final long LOGIN_TOKEN_EXPIRE_HOURS = 2L;
}
