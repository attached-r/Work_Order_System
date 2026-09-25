package com.rj.mq;

import com.rj.model.pojo.MqMessageReliability;

import java.util.List;

/**
 * 工单通知事件:携带<strong>已落库</strong>的 outbox 行。
 * <p>
 * 事件里带的是「行」而不是「要发的消息」,因为它唯一的消费者
 * ({@link NotifyMessagePublisher})需要的是 {@code id} 与 {@code msg_body}——
 * 它要按 {@code id} 做 CAS 认领,并按 {@code msg_body} 逐字节发送。
 * <p>
 * 一批事件携带 N 行(而非 N 个事件各带 1 行):{@code closeTimeout()} 一轮最多关闭
 * {@code TIMEOUT_BATCH_SIZE} 单,逐条 {@code publishEvent} 会让「提交后」的回调被触发 N 次,
 * 每次各自借还 Channel、各等一次 confirm。落库仍然是逐行(幂等键 {@code uk_msg_id}
 * 需要与工单一一对应),只有事件与发送是批量的。
 */
public record WorkOrderNotifyEvent(List<MqMessageReliability> rows) {

    public WorkOrderNotifyEvent {
        rows = List.copyOf(rows);
    }
}
