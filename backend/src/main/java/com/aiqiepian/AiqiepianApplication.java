package com.aiqiepian;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 直播自动切片工具 · 单机版后端入口。
 * 本期引擎为 Mock 实现（engine.mode=mock），由 PipelineOrchestrator 定时推进六环节管线。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.aiqiepian.mapper")
public class AiqiepianApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiqiepianApplication.class, args);
    }
}
