package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改用户基本信息请求
 * <p>
 * 不含账号与密码:账号是唯一登录标识不可改,密码走单独的重置接口。
 * departmentId 可空,为空表示本次不调整部门(MyBatis-Plus 默认跳过 null 字段)。
 * <p>
 * phone 用"null 与空串区分两种意图":不传(null)表示不修改,传空串表示清空。
 * 前端要在"留空=不改"和"想清空"之间做选择,就必须有这两种表达方式。
 */
@Data
@Schema(description = "修改用户基本信息请求")
public class UpdateUserDTO {

    @Schema(description = "真实姓名")
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 50, message = "真实姓名长度不能超过50个字符")
    private String realName;

    @Schema(description = "联系电话;传空字符串表示清空,不传(null)表示不修改")
    @Size(max = 20, message = "联系电话长度不能超过20个字符")
    private String phone;

    @Schema(description = "所属部门ID,可空表示不调整")
    @Positive(message = "部门ID必须为正整数")
    private Long departmentId;
}
