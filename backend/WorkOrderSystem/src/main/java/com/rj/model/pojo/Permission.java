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
@TableName("permission")
@Schema(description = "权限实体")
public class Permission {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "权限标识:如 workorder:review/workorder:dispatch")
    private String permCode;

    @Schema(description = "权限名称:如 审核工单")
    private String permName;

    @Schema(description = "父权限ID,支持权限树;一级权限为 NULL")
    private Long parentId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
