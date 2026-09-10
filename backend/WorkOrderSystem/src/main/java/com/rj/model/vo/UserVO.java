package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * User 返回视图
 * */
@Data
public class UserVO {

    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "用户名")
    private  String username;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "所属部门id")
    private Long departmentId;

    @Schema(description = "所属部门名称")
    private String departmentName;

    @Schema(description = "状态：1启用 0停用")
    private Integer status;

    @Schema(description = "角色标识列表,如 SUBMITTER")
    private List<String> roles;

    @Schema(description = "权限码列表,如 workorder:review")
    private List<String> perms;

}
