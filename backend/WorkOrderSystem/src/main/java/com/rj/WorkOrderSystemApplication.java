package com.rj;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类。
 * <p>
 * {@code @EnableScheduling} 开启定时任务支持,工单超时自动关闭
 * ({@link com.rj.job.WorkOrderTimeoutJob}) 依赖它才会被调度。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.rj.mapper")
public class WorkOrderSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkOrderSystemApplication.class, args);
    }

}
