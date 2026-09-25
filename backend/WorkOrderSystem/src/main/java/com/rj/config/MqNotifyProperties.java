package com.rj.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步通知(模块三)配置项,对应 application.yaml 中的 mq.notify.*。
 * <p>
 * {@code enabled} 默认 false:本机/CI 没有 broker 时必须能正常启动,
 * 此时交换机、队列、监听器都不注册,补偿任务只把消息留在本地消息表等待人工处理。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mq.notify")
public class MqNotifyProperties {

    /** 总开关:false 时只落库不投递,也不注册监听器 */
    private boolean enabled = false;

    /** 业务 topic 交换机 */
    private String exchange = "workorder.notify.exchange";

    /** 业务队列(绑定死信交换机) */
    private String queue = "workorder.notify.queue";

    /** 路由键前缀,routingKey = prefix + 事件名小写,如 workorder.notify.submit */
    private String routingKeyPrefix = "workorder.notify.";

    /** 死信交换机 */
    private String deadLetterExchange = "workorder.notify.dlx";

    /** 死信队列 */
    private String deadLetterQueue = "workorder.notify.dlq";

    /** 死信路由键 */
    private String deadLetterRoutingKey = "workorder.notify.dead";

    /** 同步等待 broker confirm 的超时时间(秒) */
    private long confirmTimeoutSeconds = 3L;

    /** 补偿任务配置 */
    private Compensation compensation = new Compensation();

    /**
     * 本地消息表补偿任务配置:扫描投递失败或超时未确认的消息并重发。
     */
    @Data
    public static class Compensation {

        /** 补偿任务开关 */
        private boolean enabled = true;

        /** 扫描间隔(毫秒) */
        private long scanDelayMs = 30000L;

        /** 单次扫描最多处理条数 */
        private int batchSize = 200;

        /** 最大重试次数,超过则标记为失败(status=3) */
        private int maxRetry = 5;

        /** 退避基数(秒),第 n 次重试等待 backoffSeconds * n 秒 */
        private long backoffSeconds = 30L;
    }
}
