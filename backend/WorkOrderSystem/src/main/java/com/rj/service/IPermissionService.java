package com.rj.service;

import com.rj.model.vo.PermissionVO;

import java.util.List;

/**
 * 权限字典操作接口
 */
public interface IPermissionService {

    /** 权限树:按 parent_id 装配成多级结构,供权限分配控件渲染 */
    List<PermissionVO> tree();
}
