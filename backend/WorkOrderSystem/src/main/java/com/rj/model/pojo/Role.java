package com.rj.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
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
@TableName("role")
@Schema(description = "角色实体")
public class Role {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "角色标识:SUBMITTER/REVIEWER/DISPATCHER/HANDLER/ADMIN(代码判断用)")
    private String roleCode;

    @Schema(description = "角色名称:提单人/审核人/派单人/处理人/管理员(展示用)")
    private String roleName;

    @Schema(description = "职责说明")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
