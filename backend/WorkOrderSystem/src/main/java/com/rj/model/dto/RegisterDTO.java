package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数
 * 账号/密码/真实姓名必填;手机号与部门选填。
 * 密码与确认密码的一致性在 Service 层校验。
 */
@Data
@Schema(description = "注册请求参数")
public class RegisterDTO {

    @Schema(description = "登录账号")
    @NotBlank(message = "账号不能为空")
    @Size(max = 50, message = "账号长度不能超过50个字符")
    private String username;

    @Schema(description = "密码")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码至少6位")
    private String password;

    @Schema(description = "确认密码,需与密码一致")
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    @Schema(description = "真实姓名")
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 50, message = "真实姓名长度不能超过50个字符")
    private String realName;

    @Schema(description = "联系电话,可空")
    @Size(max = 20, message = "联系电话长度不能超过20个字符")
    private String phone;

    @Schema(description = "所属部门ID,可空")
    @Positive(message = "部门ID必须为正整数")
    private Long departmentId;
}
