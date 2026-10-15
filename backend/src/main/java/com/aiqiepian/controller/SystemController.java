package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.engine.Recorder;
import com.aiqiepian.engine.real.FfmpegSupport;
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
    private final Recorder recorder;

    public SystemController(ConfigurableApplicationContext context, Recorder recorder) {
        this.context = context;
        this.recorder = recorder;
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
            // 先关闸：从现在起不再启动新的 ffmpeg/python 子进程（被打断的渲染会试图回退重剪，
            // 那种进程没有线程等它，钩子跑完就成孤儿）。
            FfmpegSupport.markShuttingDown();
            // 先把录制收尾（合并分段）再关容器：容器关闭路径上调度器可能正卡在
            // 平台接口的 socket read 里，@PreDestroy 未必被执行，遗留分片会让整场录制不可分析。
            try {
                recorder.drainForShutdown();
            } catch (Exception e) {
                log.warn("退出前收尾录制异常: {}", e.toString());
            }
            // 容器关闭必须有上限。实测卡点：页面里正在播放媒体流时，Tomcat 的 graceful close
            // 会一直等在途请求上，SpringApplication.exit 永不返回——进程看似"点了停止但没停"，
            // 还继续锁着 jar 文件，下一次打包就会失败（Unable to rename ...jar.original）。
            Thread closer = new Thread(() -> {
                try {
                    SpringApplication.exit(context, () -> 0);
                } catch (Exception e) {
                    log.warn("Spring 容器关闭异常: {}", e.toString());
                }
            }, "graceful-context-close");
            closer.setDaemon(true);
            closer.start();
            try {
                closer.join(15_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (closer.isAlive()) {
                log.warn("容器关闭 15 秒未完成（通常是页面还挂着媒体流/长连接），强制退出 JVM；"
                        + "录制已在前面收尾，下面的子进程清理会兜住孤儿");
            }
            // halt 不跑 shutdown hook，所以子进程清理必须在这里**同步**做一次：
            // 只挂钩子等于给这条路径留口子（实测一键停止后斗鱼弹幕旁路 python 还活着占着文件）。
            FfmpegSupport.reapNow();
            Runtime.getRuntime().halt(0);
        }, "graceful-exit");
        t.setDaemon(false);
        t.start();
        return ApiResponse.ok(true);
    }
}
