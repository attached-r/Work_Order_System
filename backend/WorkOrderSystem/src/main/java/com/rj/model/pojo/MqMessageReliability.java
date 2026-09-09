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
@TableName("mq_message_reliability")
@Schema(description = "MQ 消息可靠性实体")
public class MqMessageReliability {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "消息唯一ID(业务侧生成)")
    private String msgId;

    @Schema(description = "业务类型:如 workorder_notify")
    private String bizType;

    @Schema(description = "业务单据ID,可空")
    private String bizId;

    @Schema(description = "交换机名称")
    private String exchange;

    @Schema(description = "路由键")
    private String routingKey;

    @Schema(description = "消息体(JSON)")
    private String msgBody;

    @Schema(description = "发送状态:0待发送 1已投递待确认 2已确认 3失败")
    private Integer status;

    @Schema(description = "已重试次数")
    private Integer retryCount;

    @Schema(description = "下次重试时间")
    private LocalDateTime nextRetryTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最近发送时间")
    private LocalDateTime sendTime;

    @Schema(description = "确认成功时间")
    private LocalDateTime successTime;
}
