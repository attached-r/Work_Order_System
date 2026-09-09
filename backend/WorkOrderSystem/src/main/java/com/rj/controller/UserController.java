package com.rj.controller;

import com.rj.common.Result;
import com.rj.model.dto.LoginDTO;
import com.rj.model.dto.RegisterDTO;
import com.rj.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="用户管理")
@RequestMapping("/user")
@RestController
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    /**
     * 登录成功后返回 token。
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginDTO loginDTO) {
        return userService.login(loginDTO.getUsername(), loginDTO.getPassword());
    }

    /**
     * 注册成功后仅返回成功结果,由前端引导用户去登录。
     */
    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO registerDTO) {
        return userService.register(registerDTO);
    }
}
