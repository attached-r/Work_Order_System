package com.rj.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收件箱展示模型。
 * <p>
 * 与实体 {@code Notification} 的差别只有两点:多了 {@code notifyTypeDesc}(类型中文,
 * 前端不必自带字典),以及不含 {@code msgId} / {@code userId}——
 * {@code userId} 是服务端强制注入的查询条件,回传给前端没有意义,
 * 反而给「换个 userId 试试」留下了锚点。
 */
@Data
@Schema(description = "站内信通知(收件箱项)")
public class NotificationVO {

    @Schema(description = "通知ID")
    private Long id;

    @Schema(description = "类型码 1-10,取值同工单操作事件")
    private Integer notifyType;

    @Schema(description = "类型中文,如「审核驳回」")
    private String notifyTypeDesc;

    @Schema(description = "渠道:1站内信 2邮件 3短信")
    private Integer channel;

    @Schema(description = "标题(已渲染)")
    private String title;

    @Schema(description = "正文(已渲染)")
    private String content;

    @Schema(description = "业务类型:workorder_notify")
    private String bizType;

    @Schema(description = "业务ID(工单ID),前端用于跳转工单详情")
    private String bizId;

    @Schema(description = "工单编号")
    private String orderNo;

    @Schema(description = "0未读 1已读")
    private Integer readFlag;

    @Schema(description = "已读时间,可空")
    private LocalDateTime readTime;

    @Schema(description = "产生时间")
    private LocalDateTime createTime;
}
