package com.rj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rj.model.pojo.Permission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限表mapper接口 使用mybatis-plus简化手写sql
 * */
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 查询用户通过角色间接拥有的全部权限码(去重)
     */
    @Select("SELECT DISTINCT p.perm_code FROM permission p "
            + "JOIN role_permission rp ON rp.permission_id = p.id "
            + "JOIN user_role ur ON ur.role_id = rp.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
