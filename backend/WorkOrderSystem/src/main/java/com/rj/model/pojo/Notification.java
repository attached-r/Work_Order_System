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

/**
 * 站内信实体。
 * <p>
 * 落库的 {@code title}/{@code content} 是<strong>已渲染的成品文本</strong>而非模板:
 * 读侧零成本,且文案字典日后改版不会追溯性改写历史通知——
 * 审计场景下「历史保持它被发出时的样子」比省几十字节重要得多。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification")
@Schema(description = "站内信通知实体")
public class Notification {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "来源消息ID,= mq_message_reliability.msg_id")
    private String msgId;

    @Schema(description = "收件人ID")
    private Long userId;

    @Schema(description = "业务类型:workorder_notify")
    private String bizType;

    @Schema(description = "业务单据ID(工单ID),可空")
    private String bizId;

    @Schema(description = "工单编号快照,可空")
    private String orderNo;

    @Schema(description = "通知类型,取值同 OperateType(1-10)")
    private Integer notifyType;

    @Schema(description = "渠道:1站内信 2邮件 3短信")
    private Integer channel;

    @Schema(description = "通知标题(已渲染)")
    private String title;

    @Schema(description = "通知正文(已渲染),可空")
    private String content;

    @Schema(description = "0未读 1已读")
    private Integer readFlag;

    @Schema(description = "首次标记已读时间,可空")
    private LocalDateTime readTime;

    @Schema(description = "产生时间")
    private LocalDateTime createTime;
}
