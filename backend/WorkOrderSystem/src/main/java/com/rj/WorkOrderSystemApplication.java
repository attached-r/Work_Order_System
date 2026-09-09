package com.rj;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.rj.mapper")
public class WorkOrderSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkOrderSystemApplication.class, args);
    }

}
