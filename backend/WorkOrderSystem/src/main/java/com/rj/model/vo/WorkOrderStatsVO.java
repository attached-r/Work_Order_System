package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工单统计视图,供工作台指标卡/状态分布与列表页的状态筛选条取数。
 * <p>
 * 状态枚举只有 8 个且固定,所以这里<b>无条件返回全部状态</b>(数量为 0 的也返回),
 * 前端拿到后直接渲染,不必再自己补齐缺失状态。
 */
@Data
@Schema(description = "工单统计")
public class WorkOrderStatsVO {

    @Schema(description = "当前可见范围内的工单总数")
    private Long total;

    @Schema(description = "各状态工单数,含数量为 0 的状态,按状态码升序")
    private List<StatusCount> statusCounts;

    /**
     * 单个状态的计数条目。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "状态计数")
    public static class StatusCount {

        @Schema(description = "状态码 0-7")
        private Integer status;

        @Schema(description = "状态名称")
        private String statusDesc;

        @Schema(description = "该状态下的工单数")
        private Long count;
    }
}
