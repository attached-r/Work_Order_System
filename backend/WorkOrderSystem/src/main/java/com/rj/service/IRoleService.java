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
}
