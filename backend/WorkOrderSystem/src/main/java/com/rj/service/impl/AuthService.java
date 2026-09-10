package com.rj.service.impl;

import com.rj.common.ResultCode;
import com.rj.common.UserAuth;
import com.rj.exception.BusinessException;
import com.rj.mapper.PermissionMapper;
import com.rj.mapper.RoleMapper;
import com.rj.mapper.UserMapper;
import com.rj.model.pojo.User;
import com.rj.service.IAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import static com.rj.common.RedisConstants.LOGIN_TOKEN_EXPIRE_HOURS;
import static com.rj.common.RedisConstants.LOGIN_USER_AUTH_KEY;

/**
 * 鉴权信息加载与缓存实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService implements IAuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 取用户鉴权信息:缓存优先
     * <p>
     * 命中缓存直接返回,并滑动续期使其与登录 token 同生命周期;未命中则回源数据库
     * 重建并写回,不强制用户重新登录。
     *
     * @param userId 用户ID
     * @return 该用户的鉴权快照(部门 + 角色码 + 权限码)
     */
    @Override
    public UserAuth getOrLoad(Long userId) {
        // 缓存 key 按 userId 隔离,一个用户一份快照
        String key = LOGIN_USER_AUTH_KEY + userId;
        // 先读缓存:命中就零 DB 查询,每次请求只付一次 Redis GET
        Object cached = redisTemplate.opsForValue().get(key);
        // 类型不符按未命中处理(脏数据),落到下面回源
        if (cached instanceof UserAuth userAuth) {
            // 滑动续期:与 token 一起保持活跃
            redisTemplate.expire(key, LOGIN_TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
            return userAuth;
        }
        // 未命中:回源数据库重建并写回,不强制用户重新登录
        UserAuth userAuth = loadFromDb(userId);
        cache(userAuth);
        log.debug("鉴权缓存未命中,已回源重建: userId={}", userId);
        return userAuth;
    }

    /**
     * 强制回源重建并写回缓存
     * <p>
     * 登录成功时调用,保证缓存里的角色与权限是最新的,而非上一次会话的残留。
     *
     * @param userId 用户ID
     */
    @Override
    public void reload(Long userId) {
        // 绕过缓存直接回源,用最新角色/权限覆盖可能已过期的旧快照
        cache(loadFromDb(userId));
    }

    /**
     * 写入鉴权缓存,TTL 与登录 token 一致。
     *
     * @param userAuth 待缓存的鉴权快照
     */
    @Override
    public void cache(UserAuth userAuth) {
        // key 取自快照内的 userId;TTL 与 token 一致,两者同生命周期
        redisTemplate.opsForValue().set(
                LOGIN_USER_AUTH_KEY + userAuth.getUserId(),
                userAuth,
                LOGIN_TOKEN_EXPIRE_HOURS, TimeUnit.HOURS
        );
    }

    /**
     * 删除鉴权缓存
     * <p>
     * 管理员变更角色或部门后调用,下次请求回源重建,使改动立即生效。
     *
     * @param userId 用户ID
     */
    @Override
    public void evict(Long userId) {
        // 删掉后下一次 getOrLoad 会回源重建,角色/部门改动立即生效
        redisTemplate.delete(LOGIN_USER_AUTH_KEY + userId);
    }

    /**
     * 从数据库组装:部门ID + 角色码 + 权限码
     */
    private UserAuth loadFromDb(Long userId) {
        // 取用户是为了拿部门ID,同时确认账号仍然存在
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 用户已被删除,token 虽未过期但登录态不该继续
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录已失效,请重新登录");
        }
        // 一次性组装:部门ID + 角色码 + 权限码;用 Set 去重(多个角色可能授予同一权限)
        return new UserAuth(
                userId,
                user.getDepartmentId(),
                new HashSet<>(roleMapper.selectRoleCodesByUserId(userId)),
                new HashSet<>(permissionMapper.selectPermCodesByUserId(userId))
        );
    }
}
