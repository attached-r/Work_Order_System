package com.rj.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web Mvc 配置：注册登录拦截器并配置放行路径
*/
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

}
