package com.agentworkorder.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目一（service-workorder）的访问配置，绑定 {@code agent.workorder.*}。
 *
 * <p>把地址集中在这里，本地 / 部署环境只需改一处即可切换项目一的位置。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "agent.workorder")
public class WorkOrderProperties {

    /** 项目一基地址；本地默认 {@code http://localhost:10001}。 */
    private String baseUrl = "http://localhost:10001";

}
