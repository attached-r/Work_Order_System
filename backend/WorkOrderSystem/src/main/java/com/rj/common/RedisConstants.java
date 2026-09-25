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

    /**
     * 未读通知数缓存 key 前缀,完整 key: notify:unread:{userId}
     * value 为未读条数的字符串。策略是「写失效 + TTL 兜底」,不用 INCR:
     * DEL 是幂等的,不可能算错;最坏情况只是下次读回源一次(索引覆盖计数,亚毫秒)。
     * 失效时机:① 消费端实际插入 >0 行时对该批收件人逐个 DEL;② 标记已读后 DEL 自己。
     */
    public static final String NOTIFY_UNREAD_KEY = "notify:unread:";

    /**
     * 未读通知数缓存过期时间(秒)。DEL 失败(Redis 抖动)时靠它自愈,
     * 最坏是 5 分钟的未读数滞后。
     */
    public static final long NOTIFY_UNREAD_EXPIRE_SECONDS = 300L;
}
