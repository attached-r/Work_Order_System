package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码请求
 * <p>
 * 由管理员为指定用户设置新密码,无需原密码;新密码在 Service 层 BCrypt 加密后落库。
 */
@Data
@Schema(description = "重置密码请求")
public class ResetPasswordDTO {

    @Schema(description = "新密码")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, message = "密码至少6位")
    private String newPassword;
}
