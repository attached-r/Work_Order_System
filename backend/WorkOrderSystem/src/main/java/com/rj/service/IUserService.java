package com.rj.service;

import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.model.dto.RegisterDTO;
import com.rj.model.dto.UpdateUserDTO;
import com.rj.model.vo.UserVO;

import java.util.List;


/**
 * 用户操作接口
 * */
public interface IUserService  {

    // 登录接口
    Result<String> login(String username, String password);

    // 注册接口
    Result<Void> register(RegisterDTO registerDTO);

    /** 当前登录用户信息(含角色与权限码) */
    UserVO me();

    /** 用户分页列表,keyword 模糊匹配账号或姓名 */
    PageResult<UserVO> pageUsers(long current, long size, String keyword);

    /** 覆盖式分配角色 */
    void assignRoles(Long userId, List<Long> roleIds);

    /** 调整所属部门 */
    void assignDepartment(Long userId, Long departmentId);

    /** 登出:删除 Redis 中的登录 token 与鉴权快照;当前用户取自 UserContext */
    void logout();

    /** 启停用账号:0禁用 1启用;禁用时立即踢下线 */
    void updateStatus(Long userId, Integer status);

    /** 重置密码:新密码 BCrypt 加密后落库,并让旧登录态失效 */
    void resetPassword(Long userId, String newPassword);

    /** 删除用户(逻辑删除),同时清理角色关联并踢下线 */
    void deleteUser(Long userId);

    /** 修改用户基本信息(姓名/电话/部门) */
    void updateUser(Long userId, UpdateUserDTO updateUserDTO);
}
