package com.rj.util;

import com.rj.common.ResultCode;
import com.rj.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 密码工具:基于 Spring Security 的 {@link BCryptPasswordEncoder} 封装。
 * BCrypt 自带随机盐、恒定时间比较,输出 60 位自描述 hash,线程安全可单例复用。
 * 强度(cost)由配置 password.bcrypt.strength 控制。
 */
@Slf4j
@Component
public class PasswordUtil {

    /** BCrypt 有效载荷上限 72 字节,超长会被截断造成“意外等价” */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder;

    public PasswordUtil(@Value("${password.bcrypt.strength:10}") int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    /**
     * 注册/修改密码时加密;空或超长(72 字节)拒绝。
     */
    public String encode(String rawPassword) {
        requireRawPassword(rawPassword);
        return encoder.encode(rawPassword);
    }

    /**
     * 登录校验;空输入直接返回 false。
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.isBlank()
                || encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }
        return encoder.matches(rawPassword, encodedPassword);
    }

    private void requireRawPassword(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码不能为空");
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码长度不能超过72字节");
        }
    }
}
