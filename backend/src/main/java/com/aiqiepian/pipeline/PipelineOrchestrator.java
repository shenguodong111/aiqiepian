package com.aiqiepian.pipeline;

import com.aiqiepian.engine.Publisher;
import com.aiqiepian.engine.Recorder;
import com.aiqiepian.engine.Reviewer;
import com.aiqiepian.engine.StreamMonitor;
import com.aiqiepian.engine.mock.MockSupport;
import com.aiqiepian.engine.model.LiveEvent;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.engine.model.PublishResult;
import com.aiqiepian.engine.model.ReviewDecision;
import com.aiqiepian.engine.model.ReviewRules;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.ReviewRecord;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.mapper.PipelineJobMapper;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.mapper.ReviewRecordMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.SettingsService;
import com.aiqiepian.service.impl.SettingsServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管线编排器：每 3s tick，逻辑逐步对照 mock-server server.mjs 的 tick()。
 * 1) 监控轮询随机开播 2) 录制计时 3) job 状态机（并发上限/重试/完成后触发下一环节） 4) 发布状态机。
 */
@Component
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);
    private static final int TICK_SEC = 3;
    /** 刚起录的这段时间内即便平台报"未开播"也不结束会话：开播瞬间状态位常有延迟/抖动 */
    private static final int START_GRACE_SEC = 120;
    private static final List<String> BLOCKED_WHEN_OFF = List.of("ANALYZE", "RENDER", "REVIEW", "PUBLISH");

    private final LiveRoomMapper roomMapper;
    private final RecordingSessionMapper sessionMapper;
    private final WorkMapper workMapper;
    private final ReviewRecordMapper reviewMapper;
    private final PublishTaskMapper publishMapper;
    private final PipelineJobMapper jobMapper;
    private final SettingsService settings;
    private final PipelineService pipeline;
    private final StreamMonitor streamMonitor;
    private final Recorder recorder;
    private final Reviewer reviewer;
    private final Publisher publisher;

    /**
     * 每个房间上次"下播探测"的时间戳（毫秒）。录制中的房间不必每 3s 打一次平台接口——
     * 频繁轮询会触发平台限流，而限流失败过去被当成下播，反过来把健康的录制打断重建。
     */
    private final Map<Long, Long> lastLiveCheckAt = new ConcurrentHashMap<>();

    /** real 模式下已提交给重活线程池、尚未跑完的 job 数量（按类型）：用于兑现并发上限设置 */
    private final Map<String, Integer> inFlight = new ConcurrentHashMap<>();

    /** mock | real：real 模式下录制/监控走真实引擎（ffmpeg + B站 API），mock 行为保持原样 */
    @Value("${engine.mode:mock}")
    private String engineMode;

    private boolean realMode() {
        return "real".equalsIgnoreCase(engineMode);
    }

    /**
     * 下播探测间隔：跟随设置页"监控轮询间隔（秒）"。此前该设置根本没被读取，
     * 录制中的房间被按 3s 一次死磕平台接口，是限流→误判下播→会话反复重建的直接来源。
     * 下限 15s：真下播最多晚 15s 收尾，换来录制不再被抖动打断。
     */
    private long downCheckIntervalMs() {
        return Math.max(15, settings.getInt("monitor_interval_sec", 60)) * 1000L;
    }

    /**
     * 真实重活（分析场景检测 / 成片 ASR+编码）专用线程池：这些操作在 real 模式下可能耗时数分钟，
     * 绝不能跑在单线程 @Scheduled 的 tick() 上，否则整个调度器（监控/录制计时/其它 job）全被卡死。
     * tick 只负责把 job 推进到 SUCCESS 并把 onJobSuccess 投递到此池，随即返回。
     */
    private ExecutorService heavyExecutor;

    @PostConstruct
    void initExecutor() {
        int size = Math.max(1, settings.getInt("max_concurrent_heavy", 2));
        heavyExecutor = Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "pipeline-heavy");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdownExecutor() {
        if (heavyExecutor == null) return;
        // 先 shutdown（不中断正在跑的线程）给"片段入库"这类短关键段一个收尾窗口，
        // 超过 45s 还在跑的重活（Whisper/编码）才强制掐掉——被掐的那次会由启动补排重跑。
        heavyExecutor.shutdown();
        try {
            if (!heavyExecutor.awaitTermination(45, TimeUnit.SECONDS)) {
                heavyExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heavyExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 把 job 成功后的真实工作移出调度线程执行；异常仅记录（job 已置 SUCCESS，与旧同步行为一致，不重复触发） */
    private void submitHeavy(PipelineJob j, LocalDateTime now) {
        if (realMode()) inFlight.compute(j.getJobType(), (k, v) -> v == null ? 1 : v + 1);
        heavyExecutor.submit(() -> {
            try {
                onJobSuccess(j, now);
            } catch (Exception e) {
                log.error("后台执行真实工作异常 type={} entityId={}: {}",
                        j.getJobType(), j.getEntityId(), e.toString(), e);
            } finally {
                if (realMode()) inFlight.compute(j.getJobType(), (k, v) -> v == null || v <= 1 ? null : v - 1);
            }
        });
    }

    /**
     * 真实引擎下 job 在表里只停留一个 tick 就变 SUCCESS，正在跑的重活不再体现于 RUNNING 行，
     * 因此并发上限改看已提交但未完成的重活数量。
     */
    private int inFlightCount(String jobType) {
        return inFlight.getOrDefault(jobType, 0);
    }

    @Scheduled(fixedRate = 3000)
    public synchronized void tick() {
        try {
            doTick();
        } catch (Exception e) {
            log.error("pipeline tick error", e);
        }
    }

    private void doTick() {
        final boolean autoRun = settings.getBool("auto_run", true);
        final int maxRetry = settings.getInt("max_retry", 3);
        final int limitAnalyze = settings.getInt("max_concurrent_analyze", 2);
        final int limitRender = settings.getInt("max_concurrent_render", 2);
        final LocalDateTime now = LocalDateTime.now();
        final long nowMs = System.currentTimeMillis();

        // 1. 监控 → 开播（mock：概率；real：B站 get_info 真实探测 0→1 开播、1→0 下播自动结束）
        final boolean real = realMode();
        if (autoRun) {
            for (LiveRoom room : roomMapper.selectList(
                    Wrappers.<LiveRoom>lambdaQuery().orderByAsc(LiveRoom::getId))) {
                if (!Boolean.TRUE.equals(room.getMonitorEnabled())) continue;
                if ("RECORDING".equals(room.getStatus())) {
                    if (real) {
                        // 房间挂着"录制中"却没有任何进行中的会话（进程被强杀后由启动收尾把会话关了，
                        // 或历史数据状态没跟回来）：不修正就永远卡在这个状态，再也不会起新录制。
                        long activeSessions = sessionMapper.selectCount(
                                Wrappers.<RecordingSession>lambdaQuery()
                                        .eq(RecordingSession::getRoomId, room.getId())
                                        .eq(RecordingSession::getStatus, "RECORDING"));
                        if (activeSessions == 0) {
                            room.setStatus("MONITORING");
                            roomMapper.updateById(room);
                            log.info("房间 {} 标记录制中但无进行中会话，已回置为监控中", room.getId());
                            continue;
                        }
                        // 录制中的房间按下播间隔（而非每 tick）探测下播：轮询越密越容易被平台限流，
                        // 而一次限流失败过去会被当成"下播"，把正在正常录制的会话打断重建。
                        if (nowMs - lastLiveCheckAt.getOrDefault(room.getId(), 0L) >= downCheckIntervalMs()) {
                            lastLiveCheckAt.put(room.getId(), nowMs);
                            LiveEvent ev = streamMonitor.check(room);
                            // 只有平台明确回答"未开播"才收摊；查询失败=状态未知，让录制继续跑
                            if (ev.isKnown() && !ev.isLive()) {
                                List<RecordingSession> actives = sessionMapper.selectList(
                                        Wrappers.<RecordingSession>lambdaQuery()
                                                .eq(RecordingSession::getRoomId, room.getId())
                                                .eq(RecordingSession::getStatus, "RECORDING"));
                                for (RecordingSession s : actives) {
                                    if (s.getStartedAt() != null
                                            && s.getStartedAt().isAfter(now.minusSeconds(START_GRACE_SEC))) {
                                        log.info("会话刚起录，忽略本次下播判定 session={} room={}", s.getId(), room.getId());
                                        continue;
                                    }
                                    log.info("真实监控确认下播，自动结束会话 session={} room={}", s.getId(), room.getId());
                                    pipeline.finishSession(s);
                                }
                            }
                        }
                    }
                    continue;
                }
                if ("OFF".equals(room.getStatus())) {
                    room.setStatus("MONITORING");
                    roomMapper.updateById(room);
                    continue;
                }
                LiveEvent event = streamMonitor.check(room);
                if (event.isLive()) {
                    boolean roomChanged = false;
                    if (real && event.getTitle() != null && !event.getTitle().isBlank()) {
                        // 直播真实标题只登记到 liveTitle 供展示；房间备注名（roomName）是用户自定义、
                        // 用于拼接作品标题的，绝不覆盖——仅当用户还没起备注名时，用直播标题补一次默认值。
                        if (!event.getTitle().equals(room.getLiveTitle())) {
                            room.setLiveTitle(event.getTitle());
                            roomChanged = true;
                        }
                        if (room.getRoomName() == null || room.getRoomName().isBlank()) {
                            room.setRoomName(event.getTitle());
                            roomChanged = true;
                        }
                    }
                    if (roomChanged) roomMapper.updateById(room);
                    // 检测到开播但平台取流未支持（如斗鱼）：只刷新状态与标题，不起录制，避免每 tick 重复失败
                    if (!recorder.supportsRealRecording(room.getPlatform())) {
                        continue;
                    }
                    try {
                        pipeline.startLive(room, false);
                    } catch (Exception e) {
                        // 真实引擎可能因房间刚下播/接口失败无法起录：记录并保持 MONITORING，下个 tick 重试
                        log.warn("startLive 失败 room={}: {}", room.getId(), e.toString());
                    }
                }
            }
        }

        // 2. 录制计时：mock 按 tick 累加假进度；real 由 ffmpeg 进程真实时长/文件体积驱动
        for (RecordingSession s : sessionMapper.selectList(Wrappers.<RecordingSession>lambdaQuery()
                .eq(RecordingSession::getStatus, "RECORDING").orderByAsc(RecordingSession::getId))) {
            int limit = s.getDurationSec() == null ? 0 : s.getDurationSec();
            int recorded;
            boolean finished;
            if (real) {
                recorded = Math.max(recorder.probeRecordedSec(s), s.getRecordedSec() == null ? 0 : s.getRecordedSec());
                long bytes = recorder.probeFileSizeBytes(s);
                if (bytes >= 0) s.setFileSizeBytes(bytes);
                // 结束条件：上限时长 / 进程退出（手动 stop 或断流下播）
                finished = !recorder.probeAlive(s) || (limit > 0 && recorded >= limit);
            } else {
                recorded = (s.getRecordedSec() == null ? 0 : s.getRecordedSec()) + TICK_SEC;
                s.setFileSizeBytes((long) recorded * 600_000L);
                finished = recorded >= limit;
            }
            s.setRecordedSec(recorded);
            PipelineJob j = pipeline.activeJob("RECORD", "session", s.getId());
            if (j != null) {
                j.setStatus("RUNNING");
                if (j.getStartedAt() == null) j.setStartedAt(now);
                int dur = limit == 0 ? 1 : limit;
                j.setProgress(Math.min(99, recorded * 100 / dur));
                jobMapper.updateById(j);
            }
            sessionMapper.updateById(s);
            if (finished) {
                pipeline.finishSession(s);
            }
        }

        // 3. job 推进（PENDING→RUNNING→SUCCESS/FAILED，FAILED 自动重试≤max_retry）
        for (PipelineJob j : jobMapper.selectList(
                Wrappers.<PipelineJob>lambdaQuery().orderByAsc(PipelineJob::getId))) {
            if ("FAILED".equals(j.getStatus()) && nz(j.getRetryCount()) < maxRetry && autoRun) {
                j.setStatus("PENDING");
                j.setRetryCount(nz(j.getRetryCount()) + 1);
                j.setProgress(0);
            }
            if ("PENDING".equals(j.getStatus())) {
                Integer cap = switch (j.getJobType()) {
                    case "ANALYZE" -> limitAnalyze;
                    case "RENDER" -> limitRender;
                    default -> null;
                };
                if (cap != null && busyCount(j.getJobType(), real) >= cap) {
                    jobMapper.updateById(j);
                    continue;
                }
                if (!autoRun && BLOCKED_WHEN_OFF.contains(j.getJobType())) {
                    jobMapper.updateById(j);
                    continue;
                }
                j.setStatus("RUNNING");
                j.setStartedAt(now);
                j.setLog(j.getJobType() + " 开始执行");
                jobMapper.updateById(j);
                continue;
            }
            if ("RUNNING".equals(j.getStatus())) {
                if (real) {
                    // 真实引擎不做假进度/随机失败：job 一进入 RUNNING 就当轮交付重活线程池执行。
                    // 过去每 3s tick 才涨 12~35 的假进度，一个分析要 4~8 轮（12~25s）才真正开跑，
                    // 还有 4% 随机"引擎超时"把好好的录制 job 刷成 FAILED 反复重试。
                    if ("RECORD".equals(j.getJobType())) {
                        RecordingSession s = sessionMapper.selectById(j.getEntityId());
                        if (s == null || !"RECORDING".equals(s.getStatus())) {
                            j.setStatus("SUCCESS");
                            j.setProgress(100);
                            j.setFinishedAt(now);
                            j.setLog(nvl(j.getLog()) + "；录制会话已结束");
                            jobMapper.updateById(j);
                        }
                        continue; // 进度由第 2 步按真实录制时长写入
                    }
                    j.setProgress(100);
                    j.setStatus("SUCCESS");
                    j.setFinishedAt(now);
                    j.setLog(nvl(j.getLog()) + "；交由真实引擎执行");
                    jobMapper.updateById(j);
                    submitHeavy(j, now);
                    continue;
                }
                if (MockSupport.chance(0.04)) {
                    j.setStatus("FAILED");
                    j.setLog(nvl(j.getLog()) + "；模拟异常：引擎超时");
                    jobMapper.updateById(j);
                    continue;
                }
                j.setProgress(Math.min(100, nz(j.getProgress()) + MockSupport.rnd(12, 35)));
                if (j.getProgress() >= 100) {
                    j.setStatus("SUCCESS");
                    j.setFinishedAt(now);
                    j.setLog(nvl(j.getLog()) + "；执行完成");
                    jobMapper.updateById(j);
                    submitHeavy(j, now);
                    continue;
                }
                jobMapper.updateById(j);
            }
        }

        // 4. 发布状态机 QUEUED→UPLOADING→PLATFORM_REVIEW→PUBLISHED(90%)/FAILED
        for (PublishTask p : publishMapper.selectList(
                Wrappers.<PublishTask>lambdaQuery().orderByAsc(PublishTask::getId))) {
            if (!autoRun) break;
            if ("PUBLISHED".equals(p.getStatus()) || "FAILED".equals(p.getStatus())) continue;
            int phase = nz(p.getPhaseSec()) + TICK_SEC;
            p.setPhaseSec(phase);
            if ("QUEUED".equals(p.getStatus()) && phase > 3) {
                p.setStatus("UPLOADING");
                p.setPhaseSec(0);
            } else if ("UPLOADING".equals(p.getStatus())) {
                p.setProgress(Math.min(99, nz(p.getPhaseSec()) * 100 / 8));
                if (nz(p.getPhaseSec()) > 8) {
                    p.setStatus("PLATFORM_REVIEW");
                    p.setPhaseSec(0);
                    p.setProgress(100);
                }
            } else if ("PLATFORM_REVIEW".equals(p.getStatus()) && phase > 5) {
                Work w = workMapper.selectById(p.getWorkId());
                PublishAccount account = settings.getJson(SettingsServiceImpl.KEY_PUBLISH_ACCOUNT, PublishAccount.class);
                PublishResult r = publisher.publish(w, account);
                if (r.isSuccess()) {
                    p.setStatus("PUBLISHED");
                    p.setRemoteId(String.format("BV1mock%06d", p.getId()));
                    p.setPublishedAt(now);
                } else {
                    p.setStatus("FAILED");
                    p.setErrorMsg(r.getErrorMsg());
                }
                if (w != null) {
                    w.setStatus("PUBLISHED".equals(p.getStatus()) ? "PUBLISHED" : "PUBLISH_FAILED");
                    w.setUpdatedAt(now);
                    workMapper.updateById(w);
                }
            }
            publishMapper.updateById(p);
        }
    }

    /** job SUCCESS 后触发下一环节（对照 server.mjs tick 第 3 步的分发） */
    private void onJobSuccess(PipelineJob j, LocalDateTime now) {
        switch (j.getJobType()) {
            case "ANALYZE" -> {
                RecordingSession s = sessionMapper.selectById(j.getEntityId());
                if (s != null) pipeline.applyAnalysis(s);
            }
            case "RENDER" -> {
                Work w = workMapper.selectById(j.getEntityId());
                if (w != null) pipeline.renderDone(w);
            }
            case "REVIEW" -> {
                Work w = workMapper.selectById(j.getEntityId());
                if (w != null) reviewDone(w, now);
            }
            default -> {
                // MONITOR/RECORD/PUBLISH：完成即结束，发布状态机单独推进
            }
        }
    }

    /** server.mjs reviewDone：审核完成后进入「待审」状态，等待人工审片（改标题/标签/描述后点通过） */
    private void reviewDone(Work w, LocalDateTime now) {
        List<String> sensitive = parseSensitiveWords(settings.getStr("sensitive_words", ""));
        ReviewRules rules = new ReviewRules(
                settings.getInt("auto_pass_score", 85),
                settings.getInt("auto_reject_score", 70),
                sensitive,
                settings.getBool("manual_on_uncertain", true));
        ReviewDecision d = reviewer.review(w, rules);

        ReviewRecord r = new ReviewRecord();
        r.setWorkId(w.getId());
        r.setDecision(d.getDecision());
        r.setConfidence(d.getConfidence());
        r.setReason(d.getReason());
        r.setOperator("system");
        r.setCreatedAt(now);
        reviewMapper.insert(r);

        if ("AUTO_REJECT".equals(d.getDecision())) {
            w.setStatus("REJECTED");
            w.setUpdatedAt(now);
            workMapper.updateById(w);
            return;
        }
        // AUTO_PASS 或不确定：统一进 PENDING_REVIEW，等人工审片改标题/标签后点「通过并发布」
        w.setStatus("PENDING_REVIEW");
        w.setUpdatedAt(now);
        workMapper.updateById(w);
        log.info("作品 {} 审核完成({})，进入待审状态，等待人工审片", w.getId(), d.getDecision());
    }

    private long runningCount(String jobType) {
        return jobMapper.selectCount(Wrappers.<PipelineJob>lambdaQuery()
                .eq(PipelineJob::getJobType, jobType)
                .eq(PipelineJob::getStatus, "RUNNING"));
    }

    /** 某类 job 当前占用中的数量：mock 看表里的 RUNNING 行，real 看已提交未完成的重活 */
    private int busyCount(String jobType, boolean real) {
        return real ? inFlightCount(jobType) : (int) runningCount(jobType);
    }

    /** 敏感词黑名单：一行一词 */
    private static List<String> parseSensitiveWords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split("\\r?\\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }
}
