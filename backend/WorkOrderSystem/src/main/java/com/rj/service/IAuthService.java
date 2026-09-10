package com.rj.service;

import com.rj.common.UserAuth;

/**
 * 登录用户鉴权信息(部门 + 角色 + 权限)的加载与缓存
 */
public interface IAuthService {

    /**
     * 取用户鉴权信息:缓存优先,未命中时回源数据库重建并写回缓存。
     * 命中缓存时一并滑动续期,使缓存与登录 token 同生命周期。
     */
    UserAuth getOrLoad(Long userId);

    /** 强制回源重建并写回缓存(登录时调用,保证缓存拿到的是最新角色/权限) */
    void reload(Long userId);

    /** 写入缓存 */
    void cache(UserAuth userAuth);

    /** 删除缓存,使角色/部门变更立即生效 */
    void evict(Long userId);
}
