package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rj.mapper.DepartmentMapper;
import com.rj.model.pojo.Department;
import com.rj.service.IDepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 部门操作实现
 */
@Service
@RequiredArgsConstructor
public class DepartmentService implements IDepartmentService {

    private final DepartmentMapper departmentMapper;

    /**
     * 全部部门,按 id 升序,供前端下拉选择。
     *
     * @return 部门列表
     */
    @Override
    public List<Department> listAll() {
        // 部门是字典数据、条数很少,直接全查;按 id 升序保证下拉顺序稳定
        return departmentMapper.selectList(new LambdaQueryWrapper<Department>().orderByAsc(Department::getId));
    }
}
