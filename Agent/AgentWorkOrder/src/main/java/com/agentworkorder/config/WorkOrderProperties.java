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

    /** 连接超时（毫秒）；项目一挂了不能让工具调用无限等。 */
    private int connectTimeoutMs = 2000;

    /** 读超时（毫秒）；写操作是本地数据库事务，10 秒足够。 */
    private int readTimeoutMs = 10000;

}
