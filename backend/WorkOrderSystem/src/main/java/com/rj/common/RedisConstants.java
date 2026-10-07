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

    // ------------------------------------------------------------------
    // 模块四:缓存与分布式锁
    // ------------------------------------------------------------------

    /**
     * 工单详情缓存 key 前缀,完整 key: workorder:detail:{id}
     * value 为 {@link com.rj.cache.RedisData} 包装的 {@link com.rj.model.vo.WorkOrderDetailVO}
     * (逻辑过期:值内带 expireAt),失效方式为「写失效(DEL)+ 逻辑过期重建 + 物理 TTL 兜底」。
     * 逻辑 TTL / 物理 TTL 见 application.yaml 的 cache.workorder-detail.*,不在此处写死。
     */
    public static final String WORKORDER_DETAIL_KEY = "workorder:detail:";

    /**
     * 工单不存在哨兵 key 前缀,完整 key: workorder:absent:{id}
     * value 固定为 "1",TTL 很短(cache.absent-ttl-seconds,默认 60 秒)。
     * 用途是防缓存穿透:查询一个库里也不存在的 id 时写哨兵,后续同 id 直接判定 404,不打 DB。
     */
    public static final String WORKORDER_ABSENT_KEY = "workorder:absent:";

    /**
     * 工单编号日内序号 key 前缀,完整 key: workorder:no:seq:{yyyyMMdd}
     * value 为整数,由 {@code INCR} 提供;首次写入时补过期(见 {@link #WORKORDER_NO_SEQ_EXPIRE_SECONDS})。
     */
    public static final String WORKORDER_NO_SEQ_KEY = "workorder:no:seq:";

    /**
     * 编号日内序号 key 的过期时间(秒),取 2 天。
     * 首次 {@code INCR} 时补设,防止「只增不删」的序号键永久驻留;
     * 跨天后自然切换新键,当日序号从 1 重新开始。
     */
    public static final long WORKORDER_NO_SEQ_EXPIRE_SECONDS = 2 * 24 * 60 * 60L;

    /**
     * 权限树字典缓存 key(全局唯一,无 {id} 段)。
     * 权限是几乎不变的字典数据,当前系统没有「增删权限」的接口,故用 TTL-only 失效是安全的。
     */
    public static final String PERMISSION_TREE_KEY = "permission:tree";

    /**
     * 角色已分配权限ID缓存 key 前缀,完整 key: role:perms:{roleId}
     * 该列表会随「分配角色权限」变化,主失效手段是写失效(assignPermissions 后 DEL),TTL 仅兜底。
     */
    public static final String ROLE_PERMS_KEY = "role:perms:";
}
