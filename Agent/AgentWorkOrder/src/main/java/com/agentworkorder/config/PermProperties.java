package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 权限缓存相关配置，绑定 {@code agent.perm.*}。
 *
 * <p>M2 用进程内 TTL 缓存用户 {@code perms}；M5 把实现换成 {@code PermCache}：
 * 默认 Redis，可回滚 memory。缓存不宜过长——否则「管理员刚改完权限，用户侧却迟迟不生效」，
 * 故 {@link #cacheTtlSeconds} 取一个「够省调用、又不至于让改权限窗口太大」的中间值（默认 60 秒）。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "agent.perm")
public class PermProperties {

    /** 用户 perms 的缓存时长（秒）；设为 0 表示不缓存（每次现查）。 */
    private long cacheTtlSeconds = 60;

    /** memory=进程内（回滚用）；redis=Redis（默认）。 */
    private String cacheStore = "memory";

    /** Redis key 前缀；key 里只放 token 的 SHA-256，不落明文 token。 */
    private String keyPrefix = "agent:perm:";
}
