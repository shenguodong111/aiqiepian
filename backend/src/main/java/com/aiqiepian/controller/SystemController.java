package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单机打包（M4）配套：一键停止。
 * POST /api/system/exit 先返回 ok，再异步 System.exit(0)。
 * System.exit 触发 JVM shutdown hook → Spring 容器 close → 各 @PreDestroy（含 RealRecorder.shutdownAll
 * 关闭录制中的 ffmpeg 子进程）执行，避免强杀 JVM 遗留孤儿 ffmpeg 持续拉流写盘。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final ConfigurableApplicationContext context;

    public SystemController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @PostMapping("/exit")
    public ApiResponse<Boolean> exit() {
        log.info("收到优雅退出请求，正在关闭（将停止录制中的 ffmpeg）...");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300); // 让本响应先回给调用方
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            SpringApplication.exit(context, () -> 0);
            System.exit(0);
        }, "graceful-exit");
        t.setDaemon(false);
        t.start();
        return ApiResponse.ok(true);
    }
}
