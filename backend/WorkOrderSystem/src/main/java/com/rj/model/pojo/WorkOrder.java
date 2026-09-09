package com.rj.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
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
@TableName("work_order")
@Schema(description = "工单主实体")
public class WorkOrder {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "工单编号(业务生成,唯一)")
    private String orderNo;

    @Schema(description = "提单人ID(user.id)")
    private Long userId;

    @Schema(description = "归属部门ID(提单时部门快照)")
    private Long departmentId;

    @Schema(description = "处理人ID,派单前为空")
    private Long handlerId;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单描述")
    private String content;

    @Schema(description = "工单类型:1故障报修 2资源申请 3需求变更")
    private Integer orderType;

    @Schema(description = "优先级:1高 2中 3低")
    private Integer priority;

    @Schema(description = "状态:0待审核 1待派单 2处理中 3待验收 4已完成 5已驳回 6已取消 7已超时")
    private Integer status;

    @Schema(description = "备注/驳回原因/关闭说明")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "超时时间,到期自动关闭(状态置 7)")
    private LocalDateTime expireTime;

    @Schema(description = "乐观锁版本号,防并发覆盖")
    @Version
    private Integer version;

    @Schema(description = "逻辑删除:0正常 1已删除")
    @TableLogic
    private Integer deleted;
}
