package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rj.common.ResultCode;
import com.rj.exception.BusinessException;
import com.rj.mapper.PermissionMapper;
import com.rj.mapper.RoleMapper;
import com.rj.mapper.RolePermissionMapper;
import com.rj.mapper.UserRoleMapper;
import com.rj.model.pojo.Permission;
import com.rj.model.pojo.Role;
import com.rj.model.pojo.RolePermission;
import com.rj.model.pojo.UserRole;
import com.rj.service.IAuthService;
import com.rj.service.IRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色操作实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService implements IRoleService {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final IAuthService authService;

    /**
     * 全部角色,按 id 升序,供前端下拉选择。
     *
     * @return 角色列表
     */
    @Override
    public List<Role> listAll() {
        // 角色是字典数据、条数很少,直接全查;按 id 升序保证下拉顺序稳定
        return roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId));
    }

    /**
     * 覆盖式分配角色权限
     * <p>
     * 先校验角色与权限ID均有效,再"清空旧关联 + 写入新关联",整个过程在一个事务里
     * (任一步失败即回滚,避免清空成功、写入失败导致角色丢权限)。
     * 权限变更会影响所有持有该角色的用户,因此逐个驱逐他们的鉴权缓存,使其立即生效。
     *
     * @param roleId        目标角色ID
     * @param permissionIds 新的权限ID集合,重复值会被去重;空列表表示回收全部权限
     * @throws BusinessException 角色不存在(404)、存在无效的权限ID(400)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        requireRole(roleId);

        // 先去重:重复的 permissionId 会让"查到的个数"与"传入个数"对不上,被误判成无效ID
        List<Long> distinctIds = permissionIds.stream().distinct().toList();
        // 用 count 反查这些权限ID是否都真实存在;空列表无需校验
        if (!distinctIds.isEmpty()) {
            Long validCount = permissionMapper.selectCount(
                    new LambdaQueryWrapper<Permission>().in(Permission::getId, distinctIds));
            if (validCount == null || validCount != distinctIds.size()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "存在无效的权限ID");
            }
        }

        // 覆盖式:先清空旧关联,再写入新关联(整段在一个事务里,中途失败会一起回滚)
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        for (Long permissionId : distinctIds) {
            rolePermissionMapper.insert(RolePermission.builder()
                    .roleId(roleId)
                    .permissionId(permissionId)
                    .build());
        }

        // 权限已变,所有持有该角色的用户缓存都要失效;逐用户驱逐,下次请求回源重建
        List<UserRole> holders = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, roleId));
        holders.forEach(userRole -> authService.evict(userRole.getUserId()));

        log.info("分配角色权限: roleId={}, permissionIds={}, 影响用户数={}",
                roleId, distinctIds, holders.size());
    }

    /**
     * 查询角色已绑定的权限ID列表
     * <p>
     * 只读接口,不做任何写入,目的是让前端的权限抽屉能把你角色当前的勾选状态回显出来
     * (与之配对的 {@link #assignPermissions} 是覆盖式写,不回显就存在误清空的风险)。
     * 权限名称不在这里返回:它是全局字典,前端用权限树接口拿一次即可。
     *
     * @param roleId 角色ID
     * @return 权限ID列表;角色无任何权限时为空列表
     * @throws BusinessException 角色ID非法(400)、角色不存在(404)
     */
    @Override
    public List<Long> listPermissionIds(Long roleId) {
        // 路径参数不经过 Bean Validation,这里显式拦一次非法ID,
        // 让「角色ID本身有问题」和「角色ID合法但查不到」能返回不同的错误码(400 / 404)
        if (roleId == null || roleId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色ID非法");
        }
        requireRole(roleId);

        // role_permission 一个角色通常只有十几行,直接取回内存里抽 permissionId,无需 join 权限表
        return rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId))
                .stream()
                .map(RolePermission::getPermissionId)
                .toList();
    }

    /** 校验角色存在,不存在即抛 404 */
    private void requireRole(Long roleId) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
    }
}
