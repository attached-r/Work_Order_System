package com.rj.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rj.config.MqNotifyProperties;
import com.rj.mapper.MqMessageReliabilityMapper;
import com.rj.model.pojo.MqMessageReliability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 提交后发送:三段式可靠性的第二段。
 * <p>
 * 流程:CAS 认领 → 批量投递 → 等 confirm → 回写终态。
 * <p>
 * 四条关键约束(违反任意一条,可靠性都会退化):
 * <ol>
 *   <li><strong>发送时机是 AFTER_COMMIT</strong>。事务未提交就发,回滚后会留下幽灵通知;
 *       {@code fallbackExecution=true} 则保证「无事务上下文调用 stage()」时也照发不误。</li>
 *   <li><strong>CAS 认领先于发送</strong>——{@code UPDATE ... WHERE id=? AND status=0}。
 *       多实例同时看到同一行时,只有影响行数为 1 的那个实例发送。</li>
 *   <li><strong>批量投递共用一个 Channel</strong>:{@code rabbitTemplate.invoke(ops -> ...)}
 *       让整批只借还一次 Channel、只等一轮 confirm。逐个 {@code convertAndSend}
 *       会让 200 条消息各自借还 Channel。</li>
 *   <li><strong>所有异常吞掉记 ERROR</strong>。此刻事务已经提交,抛异常只会污染调用方——
 *       对请求线程来说就是「工单建好了,但接口返回 500」。提交之后,任何失败都只是
 *       <em>投递失败</em>,不是<em>业务失败</em>;补偿 Job 会兜住它。</li>
 * </ol>
 * 本类受 {@code mq.notify.enabled} 门控:关闭时 bean 不存在,{@code NotifyOutboxWriter}
 * 抛出的应用事件无人监听,outbox 行停在 {@code status=0}(见设计文档 6.5)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mq.notify", name = "enabled", havingValue = "true")
public class NotifyMessagePublisher {

    /** 待发送:唯一由业务事务写入的值 */
    public static final int STATUS_PENDING = 0;
    /** 已投递待确认:已被某个实例认领,正在发送 */
    public static final int STATUS_CLAIMED = 1;
    /** 已确认:终态,补偿 Job 不再触碰 */
    public static final int STATUS_CONFIRMED = 2;
    /** 失败:重试次数耗尽的终态,需人工介入 */
    public static final int STATUS_FAILED = 3;

    private final MqMessageReliabilityMapper mqMessageReliabilityMapper;
    private final RabbitTemplate rabbitTemplate;
    private final MqNotifyProperties properties;

    /**
     * 事务提交后触发。
     * <p>
     * {@code REQUIRES_NEW} 不是可选项:AFTER_COMMIT 阶段,已提交事务的连接仍绑定在当前线程上,
     * 此时直接写库会写进那个已经结束的事务里,连接归还连接池时被回滚——<strong>回写会静默丢失</strong>。
     * 另起一个事务才能让回写真正落库。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void onNotifyEvent(WorkOrderNotifyEvent event) {
        List<MqMessageReliability> rows = event.rows();
        try {
            List<MqMessageReliability> claimed = claim(rows);
            if (claimed.isEmpty()) {
                log.debug("通知已被其他实例认领,本次无发送任务, 候选={}", rows.size());
                return;
            }
            publishBatch(claimed);
        } catch (Exception e) {
            // 事务已提交,这里绝不能向上抛:否则工单接口会返回 500,而工单其实建好了
            log.error("通知投递流程异常(工单事务已提交,不受影响), 待补偿 Job 重投, msgIds={}",
                    rows.stream().map(MqMessageReliability::getMsgId).toList(), e);
        }
    }

    /**
     * CAS 认领:只有影响行数为 1 的消息才由本实例发送。
     * <p>
     * 公开给补偿 Job 复用:「认领」是状态机的入口动作,两处各写一份 CAS SQL
     * 迟早会有一处漏改 {@code status} 条件,而那种漏洞只在多实例并发时才显现。
     *
     * @param rows 候选 outbox 行(可能已被别的实例抢先认领)
     * @return 本轮真正认领到的行
     */
    public List<MqMessageReliability> claim(List<MqMessageReliability> rows) {
        List<MqMessageReliability> claimed = new ArrayList<>(rows.size());
        for (MqMessageReliability row : rows) {
            LambdaUpdateWrapper<MqMessageReliability> wrapper = Wrappers.<MqMessageReliability>lambdaUpdate()
                    .eq(MqMessageReliability::getId, row.getId())
                    .eq(MqMessageReliability::getStatus, STATUS_PENDING)
                    .set(MqMessageReliability::getStatus, STATUS_CLAIMED)
                    .setSql("send_time = NOW()");
            if (mqMessageReliabilityMapper.update(null, wrapper) == 1) {
                claimed.add(row);
            } else {
                log.debug("消息已被其他实例认领或无待发送状态, 跳过: id={}, msgId={}", row.getId(), row.getMsgId());
            }
        }
        return claimed;
    }

    /**
     * 批量投递并要求确认。补偿 Job 也调用本方法,使「发送 + confirm + 终态回写 + 重试耗尽收口」
     * 只有一份实现,两条路径的行为不可能分叉。
     *
     * @param claimed 已完成 CAS 认领(status=1)的 outbox 行,调用方保证非空
     */
    public void publishBatch(List<MqMessageReliability> claimed) {
        // msgId -> 确认凭据。id 用 msgId,便于 confirm 回调日志与 outbox 对账
        Map<String, CorrelationData> confirmations = new LinkedHashMap<>(claimed.size());
        try {
            rabbitTemplate.invoke(operations -> {
                for (MqMessageReliability row : claimed) {
                    CorrelationData correlationData = new CorrelationData(row.getMsgId());
                    operations.send(row.getExchange(), row.getRoutingKey(), buildMessage(row), correlationData);
                    confirmations.put(row.getMsgId(), correlationData);
                }
                return null;
            });
        } catch (Exception e) {
            // 含「连接不上 broker」:整批退回待发送,由补偿 Job 按退避重投
            log.error("通知投递发送失败, 整批退回待发送, 条数={}", claimed.size(), e);
            claimed.forEach(this::markFailure);
            return;
        }
        // 发送已在同一 Channel 上完成,这里逐条结算(等 confirm 有上界,不会无限阻塞)
        for (MqMessageReliability row : claimed) {
            if (isConfirmed(confirmations.get(row.getMsgId()))) {
                markSuccess(row);
            } else {
                markFailure(row);
            }
        }
    }

    /**
     * 把 outbox 行还原成待发送的 {@link Message}——<strong>直接使用落库时的 msg_body 字节</strong>,
     * 不重新序列化。
     * <p>
     * 这样做的收益有两个:① 生产端加密器与消费端解密器不可能是两个 mapper,
     * 「发得出去、读不出来」这类问题从源头消失;② 运维从 outbox 里取 {@code msg_body}
     * 手工重放时,broker 上出现的内容与自动发送的逐字节一致,重放才有可比性。
     */
    private Message buildMessage(MqMessageReliability row) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setMessageId(row.getMsgId());
        return new Message(row.getMsgBody().getBytes(StandardCharsets.UTF_8), messageProperties);
    }

    /**
     * 同步等确认(见设计文档 6.4)。同步阻塞发生在「提交后的请求线程」上,不回吐到工单事务;
     * 异步 confirm 回调要另起事务/会话,复杂度翻倍而收益只是省掉这几秒。
     *
     * @return true 表示 broker 已 ack 且消息可路由
     */
    private boolean isConfirmed(CorrelationData correlationData) {
        if (correlationData == null) {
            // 发送阶段就失败了(异常已在上面处理),这里只防御性返回
            return false;
        }
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(properties.getConfirmTimeoutSeconds(), TimeUnit.SECONDS);
            // ack()/reason() 是 record 访问器;isAck()/getReason() 自 4.0 起已废弃待删
            if (!confirm.ack()) {
                log.error("broker 拒绝(nack): msgId={}, reason={}", correlationData.getId(), confirm.reason());
                return false;
            }
            if (correlationData.getReturned() != null) {
                // 交换机存在但路由不到队列:broker 会把消息丢弃,这同样是失败,不能让 outbox 误认为成功
                log.error("消息不可路由(returned), 请检查队列绑定: msgId={}, returned={}",
                        correlationData.getId(), correlationData.getReturned());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待 confirm 被中断: msgId={}", correlationData.getId(), e);
            return false;
        } catch (Exception e) {
            log.error("等待 confirm 超时或异常, 按失败处理: msgId={}", correlationData.getId(), e);
            return false;
        }
    }

    /** 确认成功:status=2,终态 */
    private void markSuccess(MqMessageReliability row) {
        LambdaUpdateWrapper<MqMessageReliability> wrapper = Wrappers.<MqMessageReliability>lambdaUpdate()
                .eq(MqMessageReliability::getId, row.getId())
                .eq(MqMessageReliability::getStatus, STATUS_CLAIMED)
                .set(MqMessageReliability::getStatus, STATUS_CONFIRMED)
                .setSql("success_time = NOW()");
        mqMessageReliabilityMapper.update(null, wrapper);
    }

    /**
     * 确认失败:退回待发送并退避;若这是最后一次机会则收口为 status=3。
     * <p>
     * <strong>收口为什么放在这里而不是只放在 Job 里?</strong> Job 的扫描条件是
     * {@code retry_count < maxRetry},一条已经耗尽的记录若不在此刻改成终态,
     * 就会永远停在 status=0 却再也扫不到——看不见的失败比看得见的失败危险得多。
     * <p>
     * 退避间隔为固定 {@code backoff-seconds}(文档 §6.5 按 {@code max-retry × backoff-seconds}
     * 估算 broker 容忍窗口,即 5 × 30s ≈ 2.5 分钟;配置只有基数一个参数,故不做指数放大)。
     */
    private void markFailure(MqMessageReliability row) {
        int currentRetry = row.getRetryCount() == null ? 0 : row.getRetryCount();
        int retryCount = currentRetry + 1;
        boolean exhausted = retryCount >= properties.getCompensation().getMaxRetry();
        LocalDateTime nextRetryTime = LocalDateTime.now()
                .plusSeconds(properties.getCompensation().getBackoffSeconds());

        LambdaUpdateWrapper<MqMessageReliability> wrapper = Wrappers.<MqMessageReliability>lambdaUpdate()
                .eq(MqMessageReliability::getId, row.getId())
                .eq(MqMessageReliability::getStatus, STATUS_CLAIMED)
                .set(MqMessageReliability::getStatus, exhausted ? STATUS_FAILED : STATUS_PENDING)
                .set(MqMessageReliability::getNextRetryTime, nextRetryTime)
                .setIncrBy(MqMessageReliability::getRetryCount, 1);
        mqMessageReliabilityMapper.update(null, wrapper);

        if (exhausted) {
            log.error("通知投递重试耗尽, 收口为失败(status=3), 需人工介入: id={}, msgId={}, retryCount={}",
                    row.getId(), row.getMsgId(), retryCount);
        } else {
            log.warn("通知投递失败, 已退回待发送: id={}, msgId={}, retryCount={}, nextRetryTime={}",
                    row.getId(), row.getMsgId(), retryCount, nextRetryTime);
        }
    }
}
