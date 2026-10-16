package com.agentworkorder;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * service-agent 启动类。
 *
 * <p>{@link ConfigurationPropertiesScan} 让 {@code LlmProperties} / {@code WorkOrderProperties}
 * 这类 {@code @ConfigurationProperties} 类自动被扫描并注册为 Bean，无需逐个 {@code @EnableConfigurationProperties}。
 *
 * <p>{@link MapperScan} 注册 M2 审计 Mapper；虽然 Spring Boot 的 MyBatis-Plus starter 通常能自动扫描
 * 主类所在包下的 Mapper，但显式声明让「审计表是 agent 唯一自己持有的数据」这件事一目了然。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.agentworkorder.audit.mapper")
public class AgentWorkOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentWorkOrderApplication.class, args);
    }

}
