package com.rj.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限校验:标注在 Controller 方法或类上。
 * <p>
 * 由 LoginInterceptor 读取,要求当前登录用户拥有 value 中的<strong>全部</strong>权限码,
 * 否则返回 403。方法上的注解优先于类上的注解。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 需要的权限码,如 workorder:review;多个时需全部满足 */
    String[] value();
}
