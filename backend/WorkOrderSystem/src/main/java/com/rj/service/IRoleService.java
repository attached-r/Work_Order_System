package com.rj.service;

import com.rj.model.pojo.Role;

import java.util.List;

/**
 * 角色操作接口
 */
public interface IRoleService {

    /** 全部角色(下拉选项用) */
    List<Role> listAll();

    /** 覆盖式分配角色权限:以本次提交的权限列表为准,空列表表示回收全部权限 */
    void assignPermissions(Long roleId, List<Long> permissionIds);

    /**
     * 查询角色已绑定的权限ID列表
     * <p>
     * 与 {@link #assignPermissions} 配对,供前端在权限抽屉里回显勾选状态。
     * 没有这个读方法,前端只能拿到权限字典、拿不到绑定关系,一旦在空勾选状态下保存
     * 就会按覆盖语义把该角色的权限清空。
     * 只回权限ID,权限名称由前端用权限树字典自行映射。
     *
     * @param roleId 角色ID
     * @return 该角色已绑定的权限ID列表;角色无任何权限时返回空列表
     */
    List<Long> listPermissionIds(Long roleId);
}
