package com.rj.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 登录用户的鉴权快照:角色码、权限码与所属部门。
 * <p>
 * 登录时从库中组装并缓存到 Redis,每个请求由拦截器取出放入 {@link UserContext},
 * 业务层据此判断权限与数据范围,无需重复查库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAuth {

    private Long userId;

    /** 所属部门ID;null 表示无部门/全局(如 ADMIN) */
    private Long departmentId;

    /** 角色标识集合,如 SUBMITTER/REVIEWER */
    private Set<String> roleCodes;

    /** 权限码集合,如 workorder:review */
    private Set<String> permCodes;

    /**
     * 是否拥有全部指定权限(不传权限码视为通过)
     */
    public boolean hasAll(String... required) {
        if (required == null || required.length == 0) {
            return true;
        }
        return permCodes != null && permCodes.containsAll(List.of(required));
    }
}
