package com.agentworkorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * service-agent 启动类。
 *
 * <p>{@link ConfigurationPropertiesScan} 让 {@code LlmProperties} / {@code WorkOrderProperties}
 * 这类 {@code @ConfigurationProperties} 类自动被扫描并注册为 Bean，无需逐个 {@code @EnableConfigurationProperties}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentWorkOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentWorkOrderApplication.class, args);
    }

}
