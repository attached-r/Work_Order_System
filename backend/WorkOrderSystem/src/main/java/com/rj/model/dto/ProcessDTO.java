package com.rj.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 处理完成请求参数
 * <p>
 * 处理人处理完毕后提交,工单从「2 处理中」流转到「3 待验收」。
 * 处理结果说明可选,最终以操作日志留痕。
 */
@Data
@Schema(description = "处理完成请求参数")
public class ProcessDTO {

    @Schema(description = "处理结果说明,可空")
    @Size(max = 500, message = "处理说明长度不能超过500个字符")
    private String remark;
}
