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
@TableName("work_order_operate_log")
@Schema(description = "工单操作日志实体")
public class WorkOrderOperateLog {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "工单ID")
    private Long orderId;

    @Schema(description = "操作人ID(user.id)")
    private Long operatorId;

    @Schema(description = "操作事件:1提交 2审核通过 3审核驳回 4派单 5处理完成 6验收通过 7退回处理 8转派 9撤回/取消 10超时关闭")
    private Integer operateType;

    @Schema(description = "变更前状态(0-7),首次提交为 NULL")
    private Integer fromStatus;

    @Schema(description = "变更后状态(0-7)")
    private Integer toStatus;

    @Schema(description = "派单/转派目标处理人ID,可空")
    private Long toUserId;

    @Schema(description = "操作备注,可空")
    private String remark;

    @Schema(description = "操作时间")
    private LocalDateTime createTime;
}
