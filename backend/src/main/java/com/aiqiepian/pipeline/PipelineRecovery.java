package com.aiqiepian.pipeline;

import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.PipelineJobMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.PipelineService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时补排被中断的重活（仅真实引擎）：分析、成片渲染各认一种"半途被掐死"的痕迹。
 *
 * <p>真实引擎里 job 会先被置 SUCCESS，真正的 ffmpeg/Whisper 工作才交给重活线程池执行。
 * 进程恰好在这中间结束（重启打包、崩溃、睡眠被回收），数据库里留下的是"环节已完成、产物却不存在"
 * 的矛盾态，而且没有任何东西会再回来补做——表现就是用户说的"录制完分析完却没走到下一步"：
 * <ul>
 *   <li>渲染：作品还停在 DRAFT，但它的 RENDER job 已是 SUCCESS；</li>
 *   <li>分析：会话 analyze_status 停在 RUNNING（进入 applyAnalysis 才置 RUNNING，跑完才置 DONE）。</li>
 * </ul>
 *
 * <p>判定条件刻意收窄，避免误伤用户主动留着的草稿或反复重跑十几分钟的大文件：
 * 渲染只补"有 SUCCESS 渲染 job 且无活跃 job"的，分析只补"状态仍为 RUNNING"的，各限 {@link #MAX_REQUEUE} 个。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class PipelineRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRecovery.class);
    private static final int MAX_REQUEUE = 10;

    private final WorkMapper workMapper;
    private final RecordingSessionMapper sessionMapper;
    private final PipelineJobMapper jobMapper;
    private final PipelineService pipeline;

    @Override
    public void run(ApplicationArguments args) {
        int renders = requeueInterruptedRenders();
        int analyses = requeueInterruptedAnalyses();
        if (renders > 0 || analyses > 0) {
            log.info("启动补排被中断的重活：成片渲染 {} 个、片段分析 {} 个（环节已标记完成但产物缺失）",
                    renders, analyses);
        }
    }

    private int requeueInterruptedRenders() {
        List<Work> drafts = workMapper.selectList(Wrappers.<Work>lambdaQuery()
                .eq(Work::getStatus, "DRAFT")
                .orderByDesc(Work::getId));
        int requeued = 0;
        for (Work w : drafts) {
            if (requeued >= MAX_REQUEUE) break;
            long id = w.getId() == null ? -1 : w.getId();
            if (id < 0) continue;
            if (pipeline.activeJob("RENDER", "work", id) != null) continue; // 仍在排队/执行
            boolean renderWasDone = jobMapper.selectCount(Wrappers.<PipelineJob>lambdaQuery()
                    .eq(PipelineJob::getJobType, "RENDER")
                    .eq(PipelineJob::getEntityType, "work")
                    .eq(PipelineJob::getEntityId, id)
                    .eq(PipelineJob::getStatus, "SUCCESS")) > 0;
            if (!renderWasDone) continue; // 从未排过渲染：属于用户自己留的草稿，不动
            pipeline.addJob("RENDER", "work", id);
            requeued++;
        }
        return requeued;
    }

    private int requeueInterruptedAnalyses() {
        List<RecordingSession> rows = sessionMapper.selectList(Wrappers.<RecordingSession>lambdaQuery()
                .eq(RecordingSession::getAnalyzeStatus, "RUNNING")
                .ne(RecordingSession::getStatus, "RECORDING")
                .orderByDesc(RecordingSession::getId));
        int requeued = 0;
        for (RecordingSession s : rows) {
            if (requeued >= MAX_REQUEUE) break;
            long id = s.getId() == null ? -1 : s.getId();
            if (id < 0) continue;
            if (pipeline.activeJob("ANALYZE", "session", id) != null) continue;
            pipeline.addJob("ANALYZE", "session", id);
            requeued++;
        }
        return requeued;
    }
}
