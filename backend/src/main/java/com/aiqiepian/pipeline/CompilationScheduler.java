package com.aiqiepian.pipeline;

import com.aiqiepian.service.CompilationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 合集独立小调度（M4-2）：仅处理 compilation，与 PipelineOrchestrator 六环节管线完全隔离。
 * 每 5s：渲染中合集推进假进度（0→95），并回收服务重启遗留的 RENDERING 孤儿。
 */
@Component
@RequiredArgsConstructor
public class CompilationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CompilationScheduler.class);

    private final CompilationService compilationService;

    @Scheduled(fixedRate = 5000)
    public void tick() {
        try {
            compilationService.schedulerTick();
        } catch (Exception e) {
            log.error("compilation scheduler tick error", e);
        }
    }
}
