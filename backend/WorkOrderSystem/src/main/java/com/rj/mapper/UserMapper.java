package com.rj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rj.model.pojo.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户表mapper接口 使用mybatis-plus简化手写sql
 * */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 「某部门持某角色」的用户ID列表——异步通知解析收件人的唯一查询。
     * <p>
     * 为什么不能复用既有方法:{@code IUserService.listDirectory()} 对登录用户全量返回、
     * {@code pageUsers(...)} 需要 {@code user:manage} 权限,两者都答不了这个问法。
     * <p>
     * ⚠️ <strong>必须显式写 {@code u.deleted = 0}</strong>:{@code @TableLogic} 只对
     * MyBatis-Plus <em>生成</em>的 SQL 生效,手写 {@code @Select} 不会被自动注入逻辑删除条件。
     * 少写这一句,已删除的账号会收到站内信。
     * <p>
     * {@code u.status = 1} 排除停用账号。无匹配时返回<strong>空列表</strong>而非 null,
     * 调用方必须容忍空集——「查出来了,结果是空的」是成功,不是失败。
     *
     * @param departmentId 部门ID
     * @param roleCode     角色码,如 REVIEWER / DISPATCHER
     * @return 用户ID列表,可能为空
     */
    @Select("SELECT u.id FROM `user` u "
            + "JOIN user_role ur ON ur.user_id = u.id "
            + "JOIN `role` r ON r.id = ur.role_id "
            + "WHERE u.department_id = #{departmentId} "
            + "AND r.role_code = #{roleCode} "
            + "AND u.status = 1 AND u.deleted = 0")
    List<Long> selectUserIdsByDepartmentAndRole(@Param("departmentId") Long departmentId,
                                                @Param("roleCode") String roleCode);
}
