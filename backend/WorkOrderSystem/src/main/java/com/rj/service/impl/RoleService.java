package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rj.mapper.RoleMapper;
import com.rj.model.pojo.Role;
import com.rj.service.IRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 角色操作实现
 */
@Service
@RequiredArgsConstructor
public class RoleService implements IRoleService {

    private final RoleMapper roleMapper;

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
}
