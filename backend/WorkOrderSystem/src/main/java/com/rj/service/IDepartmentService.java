package com.rj.service;

import com.rj.model.pojo.Department;

import java.util.List;

/**
 * 部门操作接口
 */
public interface IDepartmentService {

    /** 全部部门(下拉选项用) */
    List<Department> listAll();
}
