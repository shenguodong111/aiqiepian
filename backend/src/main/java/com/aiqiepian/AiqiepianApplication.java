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
        // 本机网络环境（含中间设备）会静默断开 HttpURLConnection 池化的 keep-alive 连接：
        // 表现为 B站 upos 分片上传「首片成功、末片稳定 403 AccessDenied / Unexpected end of file」——
        // 因为 JDK 对已写出请求体的 PUT 不会在失效的复用连接上重试。全局关闭 keep-alive，
        // 让每个请求（尤其逐片 PUT）走独立连接，与 biliup 每片新建请求的行为一致。
        System.setProperty("http.keepAlive", "false");
        System.setProperty("sun.net.http.retryPost", "false");
        SpringApplication.run(AiqiepianApplication.class, args);
    }
}
