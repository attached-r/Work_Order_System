package com.agentworkorder.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI(){
        return  new OpenAPI()
                .info(new Info()
                        .title("智能工单Agent系统API")
                        .description("智能工单Agent系统接口文档")
                        .version("1.0")
                );
    }

    @Bean
    public GroupedOpenApi defaultOpenApi(){
        return GroupedOpenApi.builder()
                .group("DefaultAll")
                .packagesToScan("com.agentworkorder.controller")
                .build();
        }
}
