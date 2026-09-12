package com.rj.service;

import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.model.dto.RegisterDTO;
import com.rj.model.dto.UpdateUserDTO;
import com.rj.model.vo.UserBriefVO;
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

    /**
     * 用户分页列表
     *
     * @param current      页码,从 1 开始
     * @param size         每页条数
     * @param keyword      可选,模糊匹配账号或姓名
     * @param departmentId 可选,按所属部门筛选
     * @param status       可选,按状态筛选:1启用 0停用
     * @param roleCode     可选,按角色标识筛选,如 HANDLER
     */
    PageResult<UserVO> pageUsers(long current, long size, String keyword,
                                 Long departmentId, Integer status, String roleCode);

    /**
     * 用户只读目录(全量,含部门名与角色码,不含权限码)
     * <p>
     * 与 {@link #pageUsers} 的区别有两点:一是仅需登录即可调用(不要求 user:manage),
     * 让只有派单权限的角色也能把裸ID解析成姓名;二是返回 {@link UserBriefVO} 而不是
     * {@link UserVO},刻意去掉权限码避免向普通账号暴露全量权限清单。
     *
     * @return 用户目录列表,按 id 升序;无用户时返回空列表
     */
    List<UserBriefVO> listDirectory();

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
