package com.rj.config;

import com.rj.mq.NotifyMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 异步通知(模块三)的 RabbitMQ 拓扑声明。
 * <p>
 * 拓扑:topic 交换机 {@code workorder.notify.exchange} → 队列 {@code workorder.notify.queue}
 * (绑定 {@code workorder.notify.#}),队列挂死信参数指向 {@code workorder.notify.dlx}(direct)→ DLQ。
 * <p>
 * <strong>刻意不声明 {@code RabbitTemplate} 与 {@code RabbitAdmin}</strong>:
 * 两者由 Boot 的 Rabbit 自动配置创建,并读取 {@code spring.rabbitmq.publisher-confirm-type /
 * publisher-returns / template.mandatory}。手写一个 {@code RabbitTemplate} 会静默丢掉这些设置——
 * 结果是 confirm 回调永远不触发,outbox 全部卡在 {@code status=1}。
 * 本类只声明「Boot 无从得知的业务拓扑」,以及一个业务语义的 JSON 转换器。
 * <p>
 * 整个类受 {@code mq.notify.enabled} 门控:本机没有 broker 时置 false,不注册任何交换机/队列/绑定,
 * 应用照常启动(见设计文档 6.5)。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mq.notify", name = "enabled", havingValue = "true")
public class RabbitMqConfig {

    private final MqNotifyProperties properties;

    /** 业务 topic 交换机:生产侧只认识它 */
    @Bean
    public TopicExchange workorderNotifyExchange() {
        return new TopicExchange(properties.getExchange(), true, false);
    }

    /**
     * 业务队列。携带死信参数:消息被 reject(重试耗尽)后由 broker 自动路由到 DLX,
     * 而不是被丢弃——DLQ 是「处理失败」的可观测出口。
     */
    @Bean
    public Queue workorderNotifyQueue() {
        return QueueBuilder.durable(properties.getQueue())
                .deadLetterExchange(properties.getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getDeadLetterRoutingKey())
                .build();
    }

    /**
     * 用 {@code workorder.notify.#} 通配绑定,而不是逐事件建绑定。
     * 将来新增一类通知事件只需加路由键与消费分支,拓扑不动(见设计文档 11.7)。
     */
    @Bean
    public Binding workorderNotifyBinding(Queue workorderNotifyQueue, TopicExchange workorderNotifyExchange) {
        return BindingBuilder.bind(workorderNotifyQueue)
                .to(workorderNotifyExchange)
                .with(properties.getRoutingKeyPrefix() + "#");
    }

    /** 死信交换机(direct:死信路由键是精确投递,不需要通配) */
    @Bean
    public DirectExchange workorderNotifyDlx() {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    /** 死信队列:人工排查入口,不做自动消费 */
    @Bean
    public Queue workorderNotifyDlq() {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding workorderNotifyDlqBinding(Queue workorderNotifyDlq, DirectExchange workorderNotifyDlx) {
        return BindingBuilder.bind(workorderNotifyDlq)
                .to(workorderNotifyDlx)
                .with(properties.getDeadLetterRoutingKey());
    }

    /**
     * JSON 消息转换器(Jackson 3 版;旧 {@code Jackson2JsonMessageConverter} 自 4.0 起已废弃)。
     * <p>
     * 使用 {@link NotifyMessage#jsonMapper()} 而非新建一个 mapper,让生产端、消费端、转换器
     * 共用同一套序列化配置——否则「发出去了但消费端读不出来」这类问题只能靠猜。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter(NotifyMessage.jsonMapper());
    }
}
