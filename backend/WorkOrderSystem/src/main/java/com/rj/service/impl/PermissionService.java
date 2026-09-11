package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rj.mapper.PermissionMapper;
import com.rj.model.pojo.Permission;
import com.rj.model.vo.PermissionVO;
import com.rj.service.IPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限字典操作实现
 */
@Service
@RequiredArgsConstructor
public class PermissionService implements IPermissionService {

    private final PermissionMapper permissionMapper;

    /**
     * 权限树
     * <p>
     * 权限是字典数据、条数很少,一次全查后在内存里按 parentId 装配成树,不再逐级回库。
     * parentId 指向的父权限不存在(如父权限被删的脏数据)时,该节点按一级权限处理,
     * 避免因一条脏数据导致整棵子树从结果里消失。
     *
     * @return 一级权限列表,子权限挂在各自的 children 上
     */
    @Override
    public List<PermissionVO> tree() {
        List<Permission> all = permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getId));

        // 先建 id -> VO 的索引,装配时 O(1) 定位父节点
        Map<Long, PermissionVO> voMap = new LinkedHashMap<>();
        for (Permission permission : all) {
            voMap.put(permission.getId(), toVo(permission));
        }

        // 再遍历一次:有父且父存在就挂到父节点下,否则作为根节点
        List<PermissionVO> roots = new ArrayList<>();
        for (Permission permission : all) {
            PermissionVO vo = voMap.get(permission.getId());
            PermissionVO parent = permission.getParentId() == null
                    ? null : voMap.get(permission.getParentId());
            if (parent == null) {
                roots.add(vo);
            } else {
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    /** 实体 -> 视图:只搬运展示字段;children 由 VO 自身初始化为空列表 */
    private PermissionVO toVo(Permission permission) {
        PermissionVO vo = new PermissionVO();
        vo.setId(permission.getId());
        vo.setPermCode(permission.getPermCode());
        vo.setPermName(permission.getPermName());
        vo.setParentId(permission.getParentId());
        return vo;
    }
}
