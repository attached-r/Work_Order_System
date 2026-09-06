package com.rj.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档(Knife4j / OpenAPI 3) 配置
 * <p>
 * 启动后访问 /doc.html 查看接口文档
*/
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(){
        return  new OpenAPI()
                .info(new Info()
                        .title("智能工单系统 API")
                        .description("智能工单管理系统接口文档")
                        .version("1.0")
                );
    }

    /**
     * 分组配置：包路径匹配 显示路径下全部接口
     * @return Default
     */
    @Bean
    public GroupedOpenApi defaultOpenApi(){
        return GroupedOpenApi.builder()
                .group("DefaultAll")
                .packagesToScan("com.rj.controller")
                .build();
    }

    /**
     * 分组配置：路径匹配
     * @return 用户信息分组
     */
    @Bean
    public GroupedOpenApi userOpenApi(){
        return GroupedOpenApi.builder()
                .group("用户信息模块")
                .pathsToMatch("/user/**")
                .build();
    }

    // 诸如上述 需要分组再编写
}
