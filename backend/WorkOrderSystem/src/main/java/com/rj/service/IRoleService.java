package com.rj.service;

import com.rj.model.pojo.Role;

import java.util.List;

/**
 * 角色操作接口
 */
public interface IRoleService {

    /** 全部角色(下拉选项用) */
    List<Role> listAll();
}
