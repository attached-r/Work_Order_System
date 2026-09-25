package com.rj.mq;

import com.rj.config.MqNotifyProperties;
import com.rj.mapper.MqMessageReliabilityMapper;
import com.rj.model.pojo.MqMessageReliability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 事务内的投递意图落库器:序列化 → INSERT outbox → {@code publishEvent}。
 * <p>
 * 它是三段式可靠性的<strong>第一段</strong>,也是「消息发出去了但事务回滚了」的解药:
 * 意图与业务数据在<strong>同一个事务</strong>里落库,事务回滚则意图一起消失。
 * <p>
 * 三条自我约束(违反任意一条,可靠性就塌了):
 * <ul>
 *   <li><strong>不发送</strong>——事务还没提交,此刻发出去的消息可能在回滚后成为幽灵通知;</li>
 *   <li><strong>不 try-catch</strong>——INSERT 失败必须让整个工单事务回滚,否则工单建好了却没有通知;</li>
 *   <li><strong>不门控</strong>——{@code mq.notify.enabled=false} 时 outbox 行照写(停在 status=0)。
 *       工单业务不该因为「本机没装 broker」而改变行为,补偿路径才是那个被门控的东西。</li>
 * </ul>
 * {@code publishEvent} 是纯内存方法调用,无 I/O。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyOutboxWriter {

    private final MqMessageReliabilityMapper mqMessageReliabilityMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MqNotifyProperties properties;

    /**
     * 落一条通知意图。
     *
     * @param message 已构造好的事件载荷(其 {@code toJson()} 即 msg_body)
     */
    public void stage(NotifyMessage message) {
        stage(List.of(message));
    }

    /**
     * 批量落库:每行一条 outbox 记录(幂等键 {@code msg_id} 与业务一一对应),
     * 最后只抛<strong>一个</strong>事件携带整批——发送侧的批量合并就靠这一步。
     *
     * @param messages 事件载荷列表,空列表直接返回(不抛空事件)
     */
    public void stage(List<NotifyMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<MqMessageReliability> rows = new ArrayList<>(messages.size());
        for (NotifyMessage message : messages) {
            MqMessageReliability row = MqMessageReliability.builder()
                    .msgId(message.getMsgId())
                    .bizType(message.getBizType())
                    .bizId(String.valueOf(message.getOrderId()))
                    .exchange(properties.getExchange())
                    .routingKey(properties.getRoutingKeyPrefix() + message.eventName())
                    .msgBody(message.toJson())
                    .status(NotifyMessagePublisher.STATUS_PENDING)
                    .retryCount(0)
                    // next_retry_time 是 NOT NULL:显式给值,不依赖「MP 跳过 null 字段落到 DB 默认值」这个巧合
                    .nextRetryTime(now)
                    .createTime(now)
                    .build();
            mqMessageReliabilityMapper.insert(row);
            rows.add(row);
        }
        log.info("通知意图已落库, orderId={}, 条数={}, msgIds={}",
                messages.get(0).getOrderId(), rows.size(),
                rows.stream().map(MqMessageReliability::getMsgId).toList());
        // 事务提交后由 NotifyMessagePublisher 接管(AFTER_COMMIT),此刻只广播一个内存事件
        eventPublisher.publishEvent(new WorkOrderNotifyEvent(rows));
    }
}
