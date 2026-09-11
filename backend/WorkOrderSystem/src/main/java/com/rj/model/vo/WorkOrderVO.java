package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单列表项视图
 * <p>
 * 列表只回传轻量字段:不含 content(描述)与 version/deleted 等内部字段,
 * 描述、资源明细与操作日志在详情接口 {@link WorkOrderDetailVO} 返回。
 */
@Data
@Schema(description = "工单列表项")
public class WorkOrderVO {

    @Schema(description = "工单ID")
    private Long id;

    @Schema(description = "工单编号")
    private String orderNo;

    @Schema(description = "提单人ID")
    private Long userId;

    @Schema(description = "归属部门ID")
    private Long departmentId;

    @Schema(description = "处理人ID,派单前为空")
    private Long handlerId;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单类型:1故障报修 2资源申请 3需求变更")
    private Integer orderType;

    @Schema(description = "优先级:1高 2中 3低")
    private Integer priority;

    @Schema(description = "状态:0待审核 1待派单 2处理中 3待验收 4已完成 5已驳回 6已取消 7已超时")
    private Integer status;

    @Schema(description = "状态名称")
    private String statusDesc;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "超时时间")
    private LocalDateTime expireTime;
}
