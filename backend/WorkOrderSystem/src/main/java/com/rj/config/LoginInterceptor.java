package com.rj.config;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.rj.common.ResultCode;
import com.rj.common.UserAuth;
import com.rj.common.UserContext;
import com.rj.common.annotation.RequiresPermission;
import com.rj.exception.BusinessException;
import com.rj.service.IAuthService;
import com.rj.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.rj.common.RedisConstants.LOGIN_RENEW_THRESHOLD_SECONDS;
import static com.rj.common.RedisConstants.LOGIN_TOKEN_EXPIRE_HOURS;
import static com.rj.common.RedisConstants.LOGIN_USER_TOKEN_KEY;

/**
 * 登录拦截器：校验请求头中的 token,通过后将鉴权快照写入 {@link UserContext},
 * 并按 {@link RequiresPermission} 做接口级权限校验。
 * <p>
 * 认证双保险：JWT 只保证签名与过期,Redis 中保存的当前 token 才是真正的登录态。
 * 登出/被顶号后删除或覆盖 Redis 中的 token,旧 token 即使 JWT 未过期也会失效。
 * 通过后滑动续期,刷新 Redis 中的存活时间。
 * <p>
 * 部门级数据范围不在拦截器判断,交给 Service 层按 {@link UserContext#getDepartmentId()} 过滤。
 */
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final IAuthService authService;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = resolveBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }

        try {
            Long userId = jwtUtil.parseToken(token);
            String redisKey = LOGIN_USER_TOKEN_KEY + userId;
            String currentToken = stringRedisTemplate.opsForValue().get(redisKey);

            if (userId == null || currentToken == null || !currentToken.equals(token)) {
                // Redis 中无此 token 或已被新 token 覆盖(换设备/重新登录),旧 token 失效
                throw new BusinessException(ResultCode.UNAUTHORIZED, "登录已失效,请重新登录");
            }

            // 取出鉴权快照(缓存优先,未命中回源),供权限校验与业务层使用
            UserAuth userAuth = authService.getOrLoad(userId);
            UserContext.set(userAuth);

            checkPermission(handler, userAuth);

            // 滑动续期(节流)：只要用户持续访问,登录态就保持活跃。
            // 不无条件续期——仅当剩余存活低于阈值时才写 TTL,避免每个请求都对 Redis 产生一次写。
            renewIfBelowThreshold(redisKey);
            return true;
        } catch (BusinessException e) {
            throw e;
        } catch (JWTVerificationException e) {
            // token 过期/被篡改/签名不符
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录已失效,请重新登录");
        }
        // Redis 连接等其它运行时异常不吞,交给 GlobalExceptionHandler 兜底成 500
    }

    /**
     * 接口级权限校验:方法上的 @RequiresPermission 优先于类上的。
     * 非 Controller 方法(如静态资源)不校验。
     */
    private void checkPermission(Object handler, UserAuth userAuth) {
        //
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }

        RequiresPermission required = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequiresPermission.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequiresPermission.class);
        }

        if (required != null && !userAuth.hasAll(required.value())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束必须清除 ThreadLocal,避免线程池复用时把上一个请求的 userId 带到下一个请求
        UserContext.clear();
    }

    /**
     * 滑动续期(节流):仅当键的剩余存活低于阈值({@link com.rj.common.RedisConstants#LOGIN_RENEW_THRESHOLD_SECONDS})时才续期。
     * <p>
     * 读一次 TTL 换取「绝大多数请求不再写 EXPIRE」:每请求无条件续期会让 Redis 每请求多一次写、
     * 并触发主从/AOF 传播;节流后持续活跃的用户约每小时才续一次,写入量可忽略。
     * 读 TTL 是一次 O(1)、不传播的廉价操作,换来写放大被消除。
     */
    private void renewIfBelowThreshold(String key) {
        Long remaining = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        // remaining 为 -1(键无 TTL)/ -2(键不存在)时不动:正常登录键始终带 TTL
        if (remaining != null && remaining >= 0 && remaining < LOGIN_RENEW_THRESHOLD_SECONDS) {
            stringRedisTemplate.expire(key, Duration.ofHours(LOGIN_TOKEN_EXPIRE_HOURS));
        }
    }

    /**
     * 从 Authorization 头提取 token,兼容 Bearer 前缀与裸 token
     */
    private String resolveBearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String token = authorization;
        if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            token = token.substring(BEARER_PREFIX.length()); // 截去 Bearer前缀
        }
        return StringUtils.hasText(token.trim()) ? token.trim() : null;
    }
}
