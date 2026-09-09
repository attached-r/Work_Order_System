package com.rj.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web Mvc 配置：注册登录拦截器并配置放行路径
*/
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 免登录业务接口
                        "/user/login", "/user/register",
                        // 接口文档(Knife4j / springdoc)
                        "/doc.html", "/webjars/**",
                        "/v3/api-docs/**", "/swagger-ui/**",
                        "/favicon.ico"
                );
    }
}
