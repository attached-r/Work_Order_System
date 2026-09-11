package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建（提交）工单请求参数
 * <p>
 * 提单人、归属部门由后端从登录上下文取,不信任前端传入,避免越权代提。
 * 工单提交后即进入「0 待审核」,由本部门审核人处理。
 */
@Data
@Schema(description = "创建工单请求参数")
public class WorkOrderCreateDTO {

    @Schema(description = "工单标题")
    @NotBlank(message = "工单标题不能为空")
    @Size(max = 200, message = "工单标题长度不能超过200个字符")
    private String title;

    @Schema(description = "工单描述,可空")
    @Size(max = 5000, message = "工单描述长度不能超过5000个字符")
    private String content;

    @Schema(description = "工单类型:1故障报修 2资源申请 3需求变更")
    @NotNull(message = "工单类型不能为空")
    @Min(value = 1, message = "工单类型取值1-3")
    @Max(value = 3, message = "工单类型取值1-3")
    private Integer orderType;

    @Schema(description = "优先级:1高 2中 3低")
    @NotNull(message = "优先级不能为空")
    @Min(value = 1, message = "优先级取值1-3")
    @Max(value = 3, message = "优先级取值1-3")
    private Integer priority;

    @Schema(description = "超时时间,可空;不传则默认 72 小时后到期")
    private LocalDateTime expireTime;

    @Schema(description = "资源明细列表,可空")
    @Valid
    private List<WorkOrderResourceDTO> resources;
}
