package com.rj.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// `user` 是 MySQL 保留字,MyBatis-Plus 不会自动加反引号,需随表名字面量带上
@TableName("`user`")
@Schema(description = "用户实体")
public class User {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "BCrypt 加密密码(60 字符)")
    private String password;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "所属部门ID(department.id);NULL=无部门/全局(如 ADMIN)")
    private Long departmentId;

    @Schema(description = "账号状态:0禁用 1启用")
    private Integer status;

    @Schema(description = "联系电话,可空")
    private String phone;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除:0正常 1已删除")
    @TableLogic
    private Integer deleted;
}
