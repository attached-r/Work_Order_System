package com.rj.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT 工具类：生成与校验 token
 * 依赖外部配置(密钥 有效期) 因此声明为 Spring Bean 注入使用 而非静态工具类
* */
@Component
public class JwtUtil {

    /**
     * 签名密钥 只存于服务器中(application.yaml)
     */
    @Value("${jwt.secret}")
    private String secret;

    /**
     * token有效时间 单位 s 注意下文换算
     */
    @Value("${jwt.timeout}")
    private Long timeout;

    /**
     * 生成 token 载荷中携带 userId
     */
    public String createToken(Long userId){
        return JWT.create()
                .withClaim("userId",userId) // 载荷
                .withExpiresAt(new Date(System.currentTimeMillis() + timeout * 1000)) //有效时间 换算成 ms
                .sign(Algorithm.HMAC256(secret)); // 签名算法 用 String类型的
    }

    /**
     * 校验并解析 token,成功返回 userId,失败或已过期时抛出异常
    */
    public Long parseToken(String token){
        DecodedJWT decodedJWT = JWT.require(Algorithm.HMAC256(secret)). // 传入签名密钥
                build().
                verify(token); // 校验

        // 获取 解析载荷 并转为Long 类型
        return  decodedJWT.getClaim("userId").asLong();

        // 失败处理
    }
}
