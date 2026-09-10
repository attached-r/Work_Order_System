package com.rj.service;

import com.rj.common.PageResult;
import com.rj.common.Result;
import com.rj.model.dto.RegisterDTO;
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
}
