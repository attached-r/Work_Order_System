package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 用户只读目录项
 * <p>
 * 面向"解析姓名"这个用途:工单只回传 {@code userId} / {@code handlerId} 这类裸ID,
 * 而 {@code GET /user/page} 要求 {@code user:manage},导致只有 {@code workorder:dispatch}
 * 的派单人既看不到提单人姓名、也拉不到处理人候选,派单功能直接不可用。
 * 所以这里单独开一个"登录即可"的轻量目录。
 * <p>
 * <b>与 {@link UserVO} 的唯一区别是不含 {@code perms}:</b>该接口对所有登录用户开放,
 * 若把权限码一起返回,等于任何一个普通账号都能拉到全量用户及其权限清单,属于不必要的暴露。
 * 前端真正需要的只有姓名(展示)、角色(筛处理人候选)、状态(过滤停用账号)。
 * <p>
 * {@code roles} 不能省:派单/转派的下拉要按 {@code roles} 含 HANDLER 且 {@code status == 1} 过滤候选人。
 */
@Data
@Schema(description = "用户只读目录项(不含权限码)")
public class UserBriefVO {

    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "真实姓名(前端展示用)")
    private String realName;

    @Schema(description = "所属部门id;null 表示未归属部门")
    private Long departmentId;

    @Schema(description = "所属部门名称")
    private String departmentName;

    @Schema(description = "状态:1启用 0停用")
    private Integer status;

    @Schema(description = "角色标识列表,如 HANDLER")
    private List<String> roles;
}
