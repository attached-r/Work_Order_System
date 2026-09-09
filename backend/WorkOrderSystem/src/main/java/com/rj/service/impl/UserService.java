package com.rj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rj.common.Result;
import com.rj.common.ResultCode;
import com.rj.exception.BusinessException;
import com.rj.mapper.UserMapper;
import com.rj.model.dto.RegisterDTO;
import com.rj.model.pojo.User;
import com.rj.service.IUserService;
import com.rj.util.JwtUtil;
import com.rj.util.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import static com.rj.common.RedisConstants.LOGIN_TOKEN_EXPIRE_HOURS;
import static com.rj.common.RedisConstants.LOGIN_USER_TOKEN_KEY;

/**
 * 用户操作方法实现
 * */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements  IUserService {

    private final UserMapper userMapper;
    private final PasswordUtil passwordUtil;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    /**
     *  登录
     *  通过唯一比较username 和 password
     * */
    @Override
    public Result<String> login(String username, String password) {

        // 登录名唯一 按username 查询用用户记录
        User user = userMapper.selectOne(  //通过userMapper快速构建查询语句
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );

        // 登录失败统一提示，避免泄露账号是否存在
        if (user == null || !passwordUtil.matches(password, user.getPassword())) {
            if (user != null) {
                log.warn("用户登录失败: username={}",user.getUsername());
            }
            throw new BusinessException(ResultCode.UNAUTHORIZED,"账户或密码错误");
        }

        // 账号状态:0=禁用 1=启用,禁用账号不允许登录
        if (user.getStatus() == 0) {
            log.warn("该账号已停用,禁止登录: userId={},username={}", user.getId(), user.getUsername());
            throw new BusinessException(ResultCode.FORBIDDEN, "该账号已停用");
        }

        // 确认用户存在且合法 设置token
        String token = jwtUtil.createToken(user.getId());

        // 一个用户只保留一个 有效token;重复登录会覆盖旧的token
        stringRedisTemplate.opsForValue().set(
                LOGIN_USER_TOKEN_KEY + user.getId(),
                token,
                LOGIN_TOKEN_EXPIRE_HOURS, TimeUnit.HOURS
        );

        log.info("登录成功: userId={}, userName={}",user.getId(),user.getUsername());
        return Result.success(token);

    }

    @Override
    public Result<Void> register(RegisterDTO registerDTO) {
        // 两次输入密码一致性校验
        if (!registerDTO.getPassword().equals(registerDTO.getConfirmPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "两次输入的密码不一致");
        }

        // 账号唯一性校验(username 有唯一索引,selectCount 会自动过滤逻辑删除的账号)
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, registerDTO.getUsername())
        );
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "账号已存在,请更换账号");
        }

        // 组装并落库:密码 BCrypt 加密,status 默认启用(1)
        User user = User.builder()
                .username(registerDTO.getUsername())
                .password(passwordUtil.encode(registerDTO.getPassword()))
                .realName(registerDTO.getRealName())
                .phone(registerDTO.getPhone())
                .departmentId(registerDTO.getDepartmentId())
                .status(1)
                .build();
        userMapper.insert(user);

        log.info("注册成功: userId={}, username={}", user.getId(), user.getUsername());
        return Result.success();
    }

}
