package com.rj.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("work_order_resource")
@Schema(description = "工单资源明细实体")
public class WorkOrderResource {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属工单ID")
    private Long orderId;

    @Schema(description = "资源类别:服务器/带宽/软件许可 等")
    private String resourceType;

    @Schema(description = "资源名称/规格描述")
    private String resourceName;

    @Schema(description = "数量")
    private BigDecimal quantity;

    @Schema(description = "单位:台/MBps/个 等")
    private String unit;

    @Schema(description = "申请说明,可空")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
