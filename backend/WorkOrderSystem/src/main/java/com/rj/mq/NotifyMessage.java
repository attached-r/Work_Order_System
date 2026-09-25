package com.rj.mq;

import com.rj.model.enums.OperateType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * 异步通知事件载荷。它的 JSON 序列化结果<strong>就是</strong> {@code mq_message_reliability.msg_body} 的内容。
 * <p>
 * 生产者与消费者共用本类上的 {@link #jsonMapper()},保证两端的序列化行为逐字节一致——
 * 这也是「运维重放 msg_body」能成立的前提。
 * <p>
 * ⚠️ {@code fromStatus}/{@code toStatus} 仅用于展示与排查,<strong>不得</strong>作为分支判断依据:
 * 转派是 2→2 的自环,用状态差判断会直接漏掉这一类事件。判断一律以 {@code operateType} 为准。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "异步通知事件载荷")
public class NotifyMessage {

    /** 固定业务类型,与 outbox 的 biz_type 一致 */
    public static final String BIZ_TYPE = "workorder_notify";

    @Schema(description = "消息唯一ID(UUID),与 mq_message_reliability.msg_id 一致")
    private String msgId;

    @Schema(description = "业务类型,固定 workorder_notify")
    private String bizType;

    @Schema(description = "工单ID")
    private Long orderId;

    @Schema(description = "工单编号(快照,消费端免 join)")
    private String orderNo;

    @Schema(description = "工单标题(快照)")
    private String orderTitle;

    @Schema(description = "工单归属部门(提单时快照)")
    private Long departmentId;

    @Schema(description = "提单人ID")
    private Long creatorUserId;

    @Schema(description = "处理人ID,派单前为 null")
    private Long handlerId;

    @Schema(description = "本次操作人ID,系统操作(超时关闭)时为 0")
    private Long operatorId;

    @Schema(description = "事件键,取值 1-10,见 OperateType")
    private Integer operateType;

    @Schema(description = "变更前状态,仅展示用")
    private Integer fromStatus;

    @Schema(description = "变更后状态,仅展示用")
    private Integer toStatus;

    @Schema(description = "操作备注(驳回原因等),可空")
    private String remark;

    @Schema(description = "事件发生时间")
    private LocalDateTime occurredAt;

    /**
     * 生产/消费两端共用的 JSON 映射器。
     * <p>
     * 显式关掉「遇到未知字段就失败」:消费端将来新增字段时,旧消费者读到的是同一份 body,
     * 不应因为多了个字段就把消息判成毒消息丢进 DLQ。
     */
    public static JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** 生成一个新的事件载荷,自动填充 msgId / bizType / occurredAt */
    public static NotifyMessage of(Long orderId, String orderNo, String orderTitle, Long departmentId,
                                   Long creatorUserId, Long handlerId, Long operatorId,
                                   OperateType operateType, Integer fromStatus, Integer toStatus, String remark) {
        return NotifyMessage.builder()
                .msgId(UUID.randomUUID().toString())
                .bizType(BIZ_TYPE)
                .orderId(orderId)
                .orderNo(orderNo)
                .orderTitle(orderTitle)
                .departmentId(departmentId)
                .creatorUserId(creatorUserId)
                .handlerId(handlerId)
                .operatorId(operatorId)
                .operateType(operateType.getCode())
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .remark(remark)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    /**
     * 路由键事件名:枚举名小写(如 {@code review_reject}),用于拼出
     * {@code workorder.notify.review_reject}。
     * <p>
     * 码值非法时退化为码值字符串而<strong>不抛异常</strong>:路由键是传输层的事,
     * 一个拼不出名字的事件不该让工单事务整个回滚——队列绑定的是
     * {@code workorder.notify.#},{@code workorder.notify.11} 照样能被投递,
     * 消费端再按 {@code operateType} 处理。业务侧对未知码值的严格校验在
     * {@code NotifyDispatcher} 里,两处的宽容度<strong>刻意不同</strong>。
     */
    public String eventName() {
        for (OperateType type : OperateType.values()) {
            if (type.getCode().equals(operateType)) {
                return type.name().toLowerCase(Locale.ROOT);
            }
        }
        return String.valueOf(operateType);
    }

    /** 序列化为 msg_body */
    public String toJson() {
        return jsonMapper().writeValueAsString(this);
    }

    /** 从 msg_body 反序列化 */
    public static NotifyMessage fromJson(String json) {
        return jsonMapper().readValue(json, NotifyMessage.class);
    }
}
