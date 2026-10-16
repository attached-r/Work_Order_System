package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 权限缓存相关配置，绑定 {@code agent.perm.*}。
 *
 * <p>M2 用进程内 TTL 缓存用户 {@code perms}，避免每轮对话都去打一次项目一 {@code /tool/user/info}
 * （项目一自己已用 Redis 缓存 {@code UserAuth}，agent 再打就是纯浪费）。
 * 但缓存不宜过长——否则「管理员刚改完权限，用户侧却迟迟不生效」，故 {@link #cacheTtlSeconds} 取一个
 * 「够省调用、又不至于让改权限窗口太大」的中间值（默认 60 秒）。
 *
 * <p>将来（M5）把这张进程内缓存换成 Redis 时，接口一致，业务代码不动。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "agent.perm")
public class PermProperties {

    /** 用户 perms 的缓存时长（秒）；设为 0 表示不缓存（每次现查）。 */
    private long cacheTtlSeconds = 60;
}
