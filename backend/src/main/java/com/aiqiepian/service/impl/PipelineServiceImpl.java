package com.aiqiepian.service.impl;

import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.Work;
import com.aiqiepian.engine.Analyzer;
import com.aiqiepian.engine.Recorder;
import com.aiqiepian.engine.Renderer;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.mapper.PipelineJobMapper;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.SettingsService;
import com.aiqiepian.service.TitleGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 管线核心状态迁移，逐步对照 mock-server server.mjs：
 * startLive / finishSession / analyzeDone / makeWork / renderDone / queuePublish。
 */
@Service
@RequiredArgsConstructor
public class PipelineServiceImpl implements PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineServiceImpl.class);

    /** 与 mock 的 toLocaleDateString('zh-CN') 等价，如 2026/10/9 */
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.CHINA);

    /** 发布前（可安全补全标题/简介）的作品状态集合 */
    private static final Set<String> PRE_PUBLISH = Set.of("DRAFT", "RENDERING", "READY", "APPROVED");

    private final LiveRoomMapper liveRoomMapper;
    private final RecordingSessionMapper sessionMapper;
    private final ClipMapper clipMapper;
    private final WorkMapper workMapper;
    private final PublishTaskMapper publishTaskMapper;
    private final PipelineJobMapper jobMapper;
    private final Recorder recorder;
    private final Analyzer analyzer;
    private final Renderer renderer;
    private final SettingsService settings;
    private final TitleGenerator titleGenerator;

    /** mock | real（application.yml / 启动参数优先）；real 下会话时长以 ffmpeg 真实录制为准 */
    @Value("${engine.mode:mock}")
    private String engineMode;

    private boolean realMode() {
        return "real".equalsIgnoreCase(engineMode);
    }

    /** 建作品即时返回后，用本地大模型补全标题/简介的后台单线程池（Ollama 串行，无需并发） */
    private ExecutorService enrichExecutor;

    @PostConstruct
    void initEnrichExecutor() {
        enrichExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "work-enrich");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdownEnrichExecutor() {
        if (enrichExecutor != null) enrichExecutor.shutdownNow();
    }

    @Override
    public RecordingSession startLive(LiveRoom room, boolean manual) {
        RecordingSession s = new RecordingSession();
        s.setRoomId(room.getId());
        s.setRoomName(roomLabel(room));
        s.setPlatform(room.getPlatform());
        s.setStatus("RECORDING");
        s.setStartedAt(LocalDateTime.now());
        s.setEndedAt(null);
        s.setAnalyzeStatus("NONE");
        s.setCreatedAt(LocalDateTime.now());
        // 先落库拿到自增 id：真实引擎需要用 sessionId 命名录制文件并登记进程句柄
        sessionMapper.insert(s);
        recorder.start(s, manual);
        sessionMapper.updateById(s);

        room.setStatus("RECORDING");
        room.setLastLiveAt(s.getStartedAt());
        liveRoomMapper.updateById(room);

        addJob("RECORD", "session", s.getId());
        return s;
    }

    @Override
    public void finishSession(RecordingSession s) {
        s.setStatus("FINISHED");
        s.setEndedAt(LocalDateTime.now());
        if (realMode()) {
            // 真实引擎：stop 会停进程并把真实 recordedSec/durationSec/fileSizeBytes 回写会话
            recorder.stop(s);
            if (s.getRecordedSec() == null) s.setRecordedSec(s.getDurationSec());
            PipelineJob rec = activeJob("RECORD", "session", s.getId());
            if (rec != null && !"SUCCESS".equals(rec.getStatus())) {
                rec.setStatus("SUCCESS");
                rec.setProgress(100);
                rec.setFinishedAt(LocalDateTime.now());
                rec.setLog(nvl(rec.getLog()) + "；真实录制结束，共 " + nz(s.getRecordedSec()) + " 秒");
                jobMapper.updateById(rec);
            }
        } else {
            s.setRecordedSec(s.getDurationSec());
            recorder.stop(s);
        }
        s.setAnalyzeStatus("PENDING");
        sessionMapper.updateById(s);

        LiveRoom room = liveRoomMapper.selectById(s.getRoomId());
        if (room != null) {
            room.setStatus("MONITORING");
            liveRoomMapper.updateById(room);
        }
        addJob("ANALYZE", "session", s.getId());
    }

    @Override
    public List<Clip> applyAnalysis(RecordingSession s) {
        s.setAnalyzeStatus("DONE");
        sessionMapper.updateById(s);

        List<Clip> clips = analyzer.analyze(s);
        String dir = settings.getStr("media_dir", "./media");
        int minScore = settings.getInt("auto_work_min_score", 70);
        for (Clip c : clips) {
            c.setCreatedAt(LocalDateTime.now());
            clipMapper.insert(c);
            c.setFilePath(dir + "/clips/clip_" + c.getId() + ".mp4");
            clipMapper.updateById(c);
            if (c.getScore() != null && c.getScore() >= minScore) {
                makeWork(c);
            }
        }
        // 高光集锦：real 模式下每次录制分析完成后自动产出一支选段拼接成片（时长随录制总时长自适应）
        if (realMode() && settings.getBool("reel_enabled", true)) {
            makeReelWork(s, clips);
        }
        return clips;
    }

    /**
     * 自动高光集锦：按录制总时长推导目标时长（clamp(recorded/3, 5min, 20min)），
     * 从候选片段按评分降序贪心选段直至累计时长触顶，再按时间顺序拼成区间清单，
     * 建一个集锦作品交给 RENDER job 走 {@code RealRenderer} 的 concat 渲染。
     */
    private void makeReelWork(RecordingSession s, List<Clip> clips) {
        if (clips == null || clips.isEmpty()) return;
        File source = existingFile(s.getFilePath());
        if (source == null) {
            log.info("高光集锦跳过：会话 {} 无可用录制文件", s.getId());
            return;
        }
        int recorded = s.getRecordedSec() != null && s.getRecordedSec() > 0
                ? s.getRecordedSec()
                : clips.stream().mapToInt(c -> c.getEndSec() == null ? 0 : c.getEndSec()).max().orElse(0);
        int target = (int) Math.max(300, Math.min(1200, recorded / 3.0));

        // 评分降序选段（集锦优先高分），累计时长不超目标；单段本身超目标则不收
        List<Clip> byScore = new ArrayList<>(clips);
        byScore.sort(Comparator.comparingInt((Clip c) -> c.getScore() == null ? 0 : c.getScore()).reversed());
        List<Clip> chosen = new ArrayList<>();
        int acc = 0;
        for (Clip c : byScore) {
            if (c.getStartSec() == null || c.getEndSec() == null) continue;
            int dur = c.getEndSec() - c.getStartSec();
            if (dur <= 0 || acc + dur > target) continue;
            chosen.add(c);
            acc += dur;
        }
        if (chosen.isEmpty()) {
            // 单段都超目标（极少见）：取最高分一段截到目标时长
            Clip top = byScore.get(0);
            if (top.getStartSec() == null) return;
            int end = Math.min(top.getEndSec() == null ? top.getStartSec() + target : top.getEndSec(),
                    top.getStartSec() + target);
            chosen.add(top);
            acc = end - top.getStartSec();
        }
        // 按时间顺序排列，保证集锦叙事连贯
        chosen.sort(Comparator.comparingInt(c -> c.getStartSec() == null ? 0 : c.getStartSec()));
        StringBuilder spec = new StringBuilder();
        for (Clip c : chosen) {
            if (spec.length() > 0) spec.append(';');
            spec.append(c.getStartSec()).append(',').append(c.getEndSec());
        }
        int topScore = chosen.stream().mapToInt(c -> c.getScore() == null ? 0 : c.getScore()).max().orElse(0);

        String roomName = s.getRoomName() != null && !s.getRoomName().isBlank() ? s.getRoomName() : "直播";
        Work w = new Work();
        w.setClipId(chosen.get(0).getId());
        w.setRoomName(s.getRoomName());
        w.setTitle("《" + roomName + "》" + todayTitleDate() + " 高光集锦");
        w.setDescription("");
        w.setTags(settings.getStr("publish_default_tags", "直播切片,AI剪辑"));
        w.setSubtitleEnabled(false);
        w.setSubtitleStyle("none");
        w.setAspectRatio("16:9");
        w.setStatus("DRAFT");
        w.setScore(topScore);
        w.setOutputPath(null);
        w.setSourcePath(source.getAbsolutePath());
        w.setReelSegments(spec.toString());
        LocalDateTime now = LocalDateTime.now();
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        workMapper.insert(w);
        w.setCoverPath(settings.getStr("media_dir", "./media") + "/covers/work_" + w.getId() + "_cover.jpg");
        workMapper.updateById(w);

        addJob("RENDER", "work", w.getId());
        log.info("高光集锦作品已创建 work={} 段={} 预计={}s（目标 {}s，录制 {}s）",
                w.getId(), chosen.size(), acc, target, recorded);
    }

    private static File existingFile(String path) {
        if (path == null || path.isBlank()) return null;
        File f = new File(path);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    @Override
    public Work makeWork(Clip clip) {
        String dir = settings.getStr("media_dir", "./media");
        String roomName = clip.getRoomName() != null && !clip.getRoomName().isEmpty()
                ? clip.getRoomName() : "直播";
        Work w = new Work();
        w.setClipId(clip.getId());
        w.setRoomName(clip.getRoomName());
        // 即时返回：标题走纯规则（不等本地模型），简介先留空，随后 enrichWorkAsync 用大模型补全
        TitleGenerator.Result ts = titleGenerator.suggest(clip, false);
        w.setTitle("generic".equals(ts.kind())
                ? roomName + " " + todayTitleDate() + " 精彩片段 #" + clip.getId()
                : ts.title());
        w.setDescription("");
        w.setTags(settings.getStr("publish_default_tags", "直播切片,AI剪辑"));
        w.setSubtitleEnabled(true);
        w.setSubtitleStyle("big");
        w.setAspectRatio("9:16");
        w.setStatus("DRAFT");
        w.setScore(clip.getScore());
        w.setOutputPath(null);
        LocalDateTime now = LocalDateTime.now();
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        workMapper.insert(w);
        w.setCoverPath(dir + "/covers/work_" + w.getId() + "_cover.jpg");
        workMapper.updateById(w);

        addJob("RENDER", "work", w.getId());
        enrichWorkAsync(w.getId(), clip.getId());
        return w;
    }

    /** ai_mode=local 时后台用大模型精修标题 + 生成投稿简介，只定向更新 title/description（不碰状态/产物），发布后不再改 */
    private void enrichWorkAsync(long workId, long clipId) {
        if (!"local".equals(settings.getStr("ai_mode", "mock"))) return;
        enrichExecutor.submit(() -> {
            try {
                Clip clip = clipMapper.selectById(clipId);
                Work cur = workMapper.selectById(workId);
                if (clip == null || cur == null || !PRE_PUBLISH.contains(cur.getStatus())) return;
                TitleGenerator.Result refined = titleGenerator.suggest(clip, true);
                String aiDesc = titleGenerator.generateDescription(clip, cur.getTitle());
                LambdaUpdateWrapper<Work> upd = Wrappers.<Work>lambdaUpdate()
                        .eq(Work::getId, workId)
                        .set(Work::getUpdatedAt, LocalDateTime.now());
                boolean changed = false;
                if (!"generic".equals(refined.kind())
                        && refined.title() != null && !refined.title().isBlank()) {
                    upd.set(Work::getTitle, refined.title());
                    changed = true;
                }
                if (aiDesc != null && !aiDesc.isBlank()) {
                    upd.set(Work::getDescription, aiDesc);
                    changed = true;
                }
                if (changed) workMapper.update(null, upd);
            } catch (Exception e) {
                log.warn("作品后台补全标题/简介失败 work={}: {}", workId, e.toString());
            }
        });
    }

    @Override
    public void renderDone(Work w) {
        w.setStatus("READY");
        w.setUpdatedAt(LocalDateTime.now());
        w.setOutputPath(renderer.render(w).getOutputPath());
        workMapper.updateById(w);
        addJob("REVIEW", "work", w.getId());
    }

    @Override
    public PublishTask queuePublish(Work w) {
        return queuePublish(w, true);
    }

    @Override
    public PublishTask queuePublish(Work w, boolean respectRoomAutoPublish) {
        if (respectRoomAutoPublish && !autoPublishAllowed(w)) {
            // 自动路径但开关/账号不满足：作品保持 APPROVED，可稍后人工发布
            return null;
        }
        List<PublishTask> active = publishTaskMapper.selectList(Wrappers.<PublishTask>lambdaQuery()
                .eq(PublishTask::getWorkId, w.getId())
                .in(PublishTask::getStatus, "QUEUED", "UPLOADING", "PLATFORM_REVIEW"));
        if (!active.isEmpty()) {
            return active.get(0);
        }
        PublishTask p = new PublishTask();
        p.setWorkId(w.getId());
        p.setWorkTitle(w.getTitle());
        p.setPlatform("bilibili");
        p.setStatus("QUEUED");
        p.setProgress(0);
        p.setPhaseSec(0);
        p.setRemoteId(null);
        p.setErrorMsg(null);
        p.setPublishedAt(null);
        p.setCreatedAt(LocalDateTime.now());
        publishTaskMapper.insert(p);

        w.setStatus("PUBLISHING");
        w.setUpdatedAt(LocalDateTime.now());
        workMapper.updateById(w);
        addJob("PUBLISH", "work", w.getId());
        return p;
    }

    @Override
    public boolean autoPublishAllowed(Work w) {
        // 三门条件：全局自动发布 && 房间自动发布（查不到房间按默认开处理）&& 发布账号已绑定
        if (!settings.getBool("auto_publish", true)) return false;
        if (settings.getJson(SettingsServiceImpl.KEY_PUBLISH_ACCOUNT, PublishAccount.class) == null) return false;
        Clip clip = w == null || w.getClipId() == null ? null : clipMapper.selectById(w.getClipId());
        LiveRoom room = clip == null || clip.getRoomId() == null ? null : liveRoomMapper.selectById(clip.getRoomId());
        return room == null || !Boolean.FALSE.equals(room.getAutoPublish());
    }

    @Override
    public PipelineJob addJob(String jobType, String entityType, long entityId) {
        PipelineJob existing = activeJob(jobType, entityType, entityId);
        if (existing != null) return existing; // 去重：同一实体同环节仅保留一个活跃 job（stop 端点与编排器下播检测可能双触发）
        PipelineJob job = new PipelineJob();
        job.setJobType(jobType);
        job.setEntityType(entityType);
        job.setEntityId(entityId);
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setRetryCount(0);
        job.setLog("");
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        return job;
    }

    @Override
    public PipelineJob activeJob(String jobType, String entityType, long entityId) {
        List<PipelineJob> list = jobMapper.selectList(Wrappers.<PipelineJob>lambdaQuery()
                .eq(PipelineJob::getJobType, jobType)
                .eq(PipelineJob::getEntityType, entityType)
                .eq(PipelineJob::getEntityId, entityId)
                .notIn(PipelineJob::getStatus, "SUCCESS", "SKIPPED")
                .orderByAsc(PipelineJob::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    private static String roomLabel(LiveRoom room) {
        // 优先用户自定义备注名（用于拼接作品标题），其次主播名，最后回退直播真实标题
        if (room.getRoomName() != null && !room.getRoomName().isEmpty()) return room.getRoomName();
        if (room.getAnchorName() != null && !room.getAnchorName().isEmpty()) return room.getAnchorName();
        if (room.getLiveTitle() != null && !room.getLiveTitle().isEmpty()) return room.getLiveTitle();
        return null;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static String todayTitleDate() {
        return java.time.LocalDate.now().format(TITLE_DATE);
    }
}
