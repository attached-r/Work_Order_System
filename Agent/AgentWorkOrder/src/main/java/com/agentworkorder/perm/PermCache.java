package com.agentworkorder.perm;

import java.time.Duration;

/**
 * 用户权限缓存的端口（M5 §8.1）。
 *
 * <p>缓存只是「省一次项目一调用」，不是权限事实来源：
 * 实现可以失败，但失败必须表现为「miss」，让 {@link PermService} 回源项目一，
 * 而不是抛异常或放行权限。
 */
public interface PermCache {

    /** 命中返回权限快照；miss / 缓存不可用返回 null。 */
    UserPerms get(String tokenHash);

    /** 写入缓存；失败只告警，不影响调用方。 */
    void put(String tokenHash, UserPerms perms, Duration ttl);
}
