package com.rj.mq;

import com.rabbitmq.client.Channel;
import com.rj.service.INotificationService;
import com.rj.service.impl.NotifyDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 通知消息消费者:手动 ACK + 存储层幂等。
 * <p>
 * <strong>异常分野</strong>(这里最容易写反,写反的后果要么是丢消息、要么是垃圾死信):
 * <table border="1">
 *   <tr><th>情况</th><th>处理</th><th>搞反了会怎样</th></tr>
 *   <tr><td>收件人解析结果是<strong>空集</strong>(例:撤回时无处理人且该部门没有派单人)</td>
 *       <td>{@code basicAck} + {@code log.warn},这是合法的「无人可发」</td>
 *       <td>重试 3 次 → 进 DLQ → 制造一条永远无法处理的死信,污染 DLQ 且要人工介入</td></tr>
 *   <tr><td>解析或写入过程<strong>本身失败</strong>(数据库连接异常、消息体损坏)</td>
 *       <td><strong>抛异常</strong> → 容器重试 → DLQ</td>
 *       <td>吞掉并 ACK → 消息永久丢失,且没有任何告警</td></tr>
 * </table>
 * 一句话区分:<strong>「查出来了,结果是空的」是成功;「没查成」才是失败。</strong>
 * <p>
 * 关于「为什么不在 {@code catch} 里手写 {@code basicNack(requeue=false)}」:容器在
 * {@code acknowledge-mode: manual} 下不会替我们 nack 一个普通异常
 * ({@code ackRequired = !isAutoAck() && (!isManual() || isRejectManual(throwable))}),
 * 真正让毒消息进 DLQ 的是重试拦截器耗尽后的 {@code RejectAndDontRequeueRecoverer}——
 * 它抛出的正是「reject 且不重新入队」语义的异常。手写 nack 会与它重复,
 * 而且一旦并发下 deliveryTag 对不上,反而可能把消息卡在未确认状态。
 * <p>
 * 另一个「不做」:消费端不消费 {@code com.rj.mq.NotifyMessage} 类型的参数,
 * 而是拿原始 {@link Message} 自行反序列化——这样生产与消费用的是同一个
 * {@link NotifyMessage#jsonMapper()},不会出现「生产端写得出、消费端读不出」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mq.notify", name = "enabled", havingValue = "true")
public class NotifyMessageConsumer {

    private final NotifyDispatcher notifyDispatcher;
    private final INotificationService notificationService;

    /**
     * 消费一条通知事件。异常<b>故意不捕获</b>,交由容器重试与 DLQ 兜底。
     *
     * @param raw         原始消息(自行反序列化,见类注释)
     * @param channel     手动 ACK 用
     * @param deliveryTag 本次投递的标记
     */
    @RabbitListener(queues = "${mq.notify.queue:workorder.notify.queue}")
    public void onMessage(Message raw, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        NotifyMessage message = NotifyMessage.fromJson(new String(raw.getBody(), StandardCharsets.UTF_8));
        List<Long> recipients = notifyDispatcher.resolveRecipients(message);

        if (recipients.isEmpty()) {
            // 合法结果,不是失败:重试一百次结果还是空集,进 DLQ 只会制造无解的垃圾
            log.warn("通知无人可发, 直接确认: msgId={}, operateType={}, orderId={}, departmentId={}",
                    message.getMsgId(), message.getOperateType(), message.getOrderId(), message.getDepartmentId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        // 幂等失败不会抛异常:撞唯一键时影响行数为 0,照常往下 ACK
        int inserted = notificationService.saveIfAbsent(message, recipients);
        channel.basicAck(deliveryTag, false);
        log.info("通知已落库: msgId={}, operateType={}, orderId={}, 收件人={}, 实际新增={}",
                message.getMsgId(), message.getOperateType(), message.getOrderId(), recipients, inserted);
    }
}
