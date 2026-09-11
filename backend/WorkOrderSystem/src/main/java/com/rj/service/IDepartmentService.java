package com.rj.service;

import com.rj.model.dto.DepartmentDTO;
import com.rj.model.pojo.Department;

import java.util.List;

/**
 * 部门操作接口
 */
public interface IDepartmentService {

    /** 全部部门(下拉选项用) */
    List<Department> listAll();

    /** 新增部门,deptCode 需唯一 */
    void add(DepartmentDTO departmentDTO);

    /** 修改部门,deptCode 需唯一(排除自身) */
    void update(Long id, DepartmentDTO departmentDTO);

    /** 删除部门,存在用户或工单引用时拒绝 */
    void delete(Long id);
}
