package com.rj.service;

import com.rj.common.Result;
import com.rj.model.dto.RegisterDTO;


/**
 * 用户操作接口
 * */
public interface IUserService  {

    // 登录接口
    Result<String> login(String username, String password);

    // 注册接口
    Result<Void> register(RegisterDTO registerDTO);
}
