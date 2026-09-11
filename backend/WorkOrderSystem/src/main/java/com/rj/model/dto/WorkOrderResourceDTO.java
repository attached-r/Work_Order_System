package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工单资源明细请求参数
 * <p>
 * 随工单一起提交,创建时整体落库到 work_order_resource;
 * 明细不单独维护状态,跟随主表工单状态流转。
 */
@Data
@Schema(description = "工单资源明细")
public class WorkOrderResourceDTO {

    @Schema(description = "资源类别:服务器/带宽/软件许可 等")
    @NotBlank(message = "资源类别不能为空")
    @Size(max = 50, message = "资源类别长度不能超过50个字符")
    private String resourceType;

    @Schema(description = "资源名称/规格描述")
    @NotBlank(message = "资源名称不能为空")
    @Size(max = 200, message = "资源名称长度不能超过200个字符")
    private String resourceName;

    @Schema(description = "数量,必须大于0")
    @NotNull(message = "数量不能为空")
    @DecimalMin(value = "0.01", message = "数量必须大于0")
    private BigDecimal quantity;

    @Schema(description = "单位:台/MBps/个 等")
    @Size(max = 20, message = "单位长度不能超过20个字符")
    private String unit;

    @Schema(description = "申请说明,可空")
    @Size(max = 500, message = "申请说明长度不能超过500个字符")
    private String remark;
}
