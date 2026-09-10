package com.rj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rj.model.pojo.Role;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色表mapper接口 使用mybatis-plus简化手写sql
 * */
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 查询用户拥有的全部角色码(联表一次取回,避免先查关联再查角色)
     */
    @Select("SELECT r.role_code FROM `role` r "
            + "JOIN user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId}")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
