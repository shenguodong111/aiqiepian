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
 * 启动时把被中断的重活重新排回队列（仅真实引擎）。
 *
 * <p>真实引擎里 ANALYZE/RENDER 的行会保持 RUNNING，由重活线程池跑完才写终态。
 * 进程恰好在这中间结束（重启打包、崩溃、睡眠被回收），数据库里就留下"行还是 RUNNING、
 * 却没有任何线程在跑"的僵尸态：既不会有人回来补做，也会一直占着并发额度把后续环节堵死。
 * <ul>
 *   <li>僵尸行回收：启动时任何 RUNNING 的 ANALYZE/RENDER 必然是上个进程留下的（此刻还没有线程在跑）
 *       → 一律打回 PENDING 重跑；RECORD 不动，由调度器按会话真实状态收尾。</li>
 *   <li>产物缺失补排：早期版本会提前把 job 写成 SUCCESS，中断后留下"DRAFT 作品 + SUCCESS 渲染 job"
 *       或"analyze_status 停在 RUNNING 的会话"，这些没有活跃 job 的按实体补排一次。</li>
 *   <li>从未跑过的补排：analyze_status 一直是 NONE 的已结束会话（录制完了但那次没人排队），
 *       开着自动运行时补排一次——否则它会永远躺着，界面表现就是"自动运行开着却什么都不产出"。</li>
 * </ul>
 *
 * <p>判定条件刻意收窄，避免误伤用户主动留着的草稿或反复重跑十几分钟的大文件：
 * 补排只认"无活跃 job"的，各限 {@link #MAX_REQUEUE} 个。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class PipelineRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRecovery.class);
    private static final int MAX_REQUEUE = 10;
    /** 短于这个时长的录制不值得分析（最小的选段窗口都装不下） */
    private static final int MIN_ANALYSABLE_SEC = 30;

    private final WorkMapper workMapper;
    private final RecordingSessionMapper sessionMapper;
    private final PipelineJobMapper jobMapper;
    private final PipelineService pipeline;
    private final com.aiqiepian.service.SettingsService settings;

    @Override
    public void run(ApplicationArguments args) {
        int requeuedRows = resetStaleHeavyJobs();
        int quarantined = quarantineStalePublishJobs();
        int renders = requeueInterruptedRenders();
        int analyses = requeueInterruptedAnalyses();
        int fresh = requeueNeverAnalysed();
        if (requeuedRows > 0) {
            log.info("启动回收上次进程遗留的重活僵尸行：打回队列重新执行 {} 个（ANALYZE/RENDER 仍标 RUNNING 但已无线程在跑）",
                    requeuedRows);
        }
        if (quarantined > 0) {
            log.warn("启动回收：{} 个上传 job 停在 RUNNING（上传不可盲目重跑，可能已在 B 站生成稿件）——"
                    + "已置为失败且不再自动重试，请到创作中心核对后手动重试", quarantined);
        }
        if (renders > 0 || analyses > 0) {
            log.info("启动补排被中断的重活：成片渲染 {} 个、片段分析 {} 个（环节已标记完成但产物缺失）",
                    renders, analyses);
        }
        if (fresh > 0) {
            log.info("启动补排从未分析过的已结束录制 {} 个（开着自动运行却一条都没跑，通常是录制时进程不在或历史遗留）", fresh);
        }
    }

    /**
     * 回收上个进程留下的 RUNNING 重活行：打回 PENDING（进度清零、retry 不增加——
     * 这是进程被掐，不是执行失败）。必须在其它补排之前跑，否则那些行会被当成"活跃 job"跳过。
     * 不含 PUBLISH：上传半途被打断时稿件可能已经提交成功，自动重跑就是重复投稿。
     */
    private int resetStaleHeavyJobs() {
        List<PipelineJob> stale = jobMapper.selectList(Wrappers.<PipelineJob>lambdaQuery()
                .eq(PipelineJob::getStatus, "RUNNING")
                .in(PipelineJob::getJobType, "ANALYZE", "RENDER", "REVIEW"));
        for (PipelineJob j : stale) {
            j.setStatus("PENDING");
            j.setProgress(0);
            j.setLog((j.getLog() == null ? "" : j.getLog()) + "；上次进程结束时正在执行，已重新排队");
            jobMapper.updateById(j);
        }
        return stale.size();
    }

    /**
     * 半途被打断的上传 job：置为失败并把 retry_count 抬到额度之外，让它停在这里等人工确认，
     * 而不是像其它环节那样自动重跑（重跑有可能在 B 站生成第二份相同稿件）。
     */
    private int quarantineStalePublishJobs() {
        List<PipelineJob> stale = jobMapper.selectList(Wrappers.<PipelineJob>lambdaQuery()
                .eq(PipelineJob::getStatus, "RUNNING")
                .eq(PipelineJob::getJobType, "PUBLISH"));
        int noRetry = settings.getInt("max_retry", 3) + 1;
        for (PipelineJob j : stale) {
            j.setStatus("FAILED");
            j.setProgress(0);
            j.setRetryCount(noRetry);
            j.setFinishedAt(null);
            j.setLog((j.getLog() == null ? "" : j.getLog())
                    + "；上次进程在上传中被中断，无法确定稿件是否已提交——请到 B 站创作中心核对后手动重试");
            jobMapper.updateById(j);
        }
        return stale.size();
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

    /**
     * 从未排过队分析的已结束会话（analyze_status 停在 NONE）：自动补一次分析。
     *
     * <p>上面那条只捞"停在 RUNNING"的，于是"录制完成但一次都没分析过"的会话会永远躺在那里——
     * 用户视角就是"自动运行明明开着，片段分析/剪辑/发布什么都没有"。分析完成会自动产出高光集锦，
     * 剪辑与审片环节跟着动起来，所以这一条是把整条管线接上的关键一环。
     *
     * <p>刻意保守：总开关关着不补（那是用户明确要停）；文件不在的不补（素材库删过）；
     * 录到不足 30 秒的不补（连最小选段窗口都装不下，跑了只产出垃圾片段）；一次最多 {@link #MAX_REQUEUE} 个。
     */
    private int requeueNeverAnalysed() {
        if (!settings.getBool("auto_run", true)) return 0;
        List<RecordingSession> rows = sessionMapper.selectList(Wrappers.<RecordingSession>lambdaQuery()
                .eq(RecordingSession::getAnalyzeStatus, "NONE")
                .ne(RecordingSession::getStatus, "RECORDING")
                .isNotNull(RecordingSession::getFilePath)
                .ge(RecordingSession::getRecordedSec, MIN_ANALYSABLE_SEC)
                .orderByDesc(RecordingSession::getId));
        int queued = 0;
        for (RecordingSession s : rows) {
            if (queued >= MAX_REQUEUE) break;
            Long raw = s.getId();
            if (raw == null) continue;
            long id = raw;
            if (s.getFilePath() == null || !java.nio.file.Files.exists(java.nio.file.Path.of(s.getFilePath()))) continue;
            if (pipeline.activeJob("ANALYZE", "session", id) != null) continue;
            pipeline.addJob("ANALYZE", "session", id);
            queued++;
        }
        return queued;
    }
}
