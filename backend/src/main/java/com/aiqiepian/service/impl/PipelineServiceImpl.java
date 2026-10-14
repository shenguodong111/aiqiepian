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
import com.aiqiepian.engine.real.ClipFingerprint;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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


    /** 发布前（可安全补全标题/简介）的作品状态集合 */
    private static final Set<String> PRE_PUBLISH = Set.of("DRAFT", "RENDERING", "READY", "APPROVED");

    /** 低于这个真实秒数的录制不做片段分析：比最小选段窗口（25s）还短，注定空跑 */
    private static final int MIN_ANALYZABLE_SEC = 30;

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
        try {
            recorder.start(s, manual);
        } catch (RuntimeException e) {
            // 起录失败（取不到流地址 / 平台限流）时这行会话永远不会有对应文件。
            // 留着它，下个 tick 会把这个"录制中"会话当正常结束再走一遍收尾+分析，
            // 白烧一个 session id 还产出空分析——直接回滚这行。
            sessionMapper.deleteById(s.getId());
            throw e;
        }
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
        int captured = nz(s.getRecordedSec());
        if (realMode() && captured < MIN_ANALYZABLE_SEC) {
            // 斗鱼/虎牙偶发"接口说在播、流其实已死"：起录几秒就停，这种会话装不下最小选段窗口
            // （窗宽 25–60s），分析只会空跑一遍 ffmpeg/Whisper 并白占重活线程。
            // 不排 ANALYZE，保持"未分析"，并把原因写进录制环节日志，界面看得到为什么没出片段。
            s.setAnalyzeStatus("NONE");
            sessionMapper.updateById(s);
            LiveRoom shortRoom = liveRoomMapper.selectById(s.getRoomId());
            if (shortRoom != null && "RECORDING".equals(shortRoom.getStatus())) {
                shortRoom.setStatus("MONITORING");
                liveRoomMapper.updateById(shortRoom);
            }
            PipelineJob rec = activeJob("RECORD", "session", s.getId());
            if (rec != null) {
                rec.setLog(nvl(rec.getLog()) + "；只录到 " + captured + " 秒，过短未做片段分析");
                jobMapper.updateById(rec);
            }
            log.info("会话 {} 只录到 {} 秒（<{}s），跳过片段分析", s.getId(), captured, MIN_ANALYZABLE_SEC);
            return;
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
        // 分析真的跑起来了才标 RUNNING，跑完才标 DONE：中途进程被重启时这一行还停在 RUNNING，
        // 启动恢复（PipelineRecovery）就能认出"被掐断的分析"并补排一次。
        s.setAnalyzeStatus("RUNNING");
        sessionMapper.updateById(s);

        List<Clip> clips = analyzer.analyze(s);
        String dir = settings.getStr("media_dir", "./media");

        // 轮播/重复内容去重：real 模式下给每个候选片段算内容指纹，与该房间已有片段+本批次已收片段比对，
        // 命中近似重复则跳过（不入库、不成片、不进集锦），使循环重播的同一段只会被剪一次。
        boolean dedup = realMode() && settings.getBool("clip_dedup_enabled", true);
        File source = dedup ? existingFile(s.getFilePath()) : null;
        if (dedup && source == null) {
            log.info("片段去重跳过：会话 {} 无可用录制文件", s.getId());
            dedup = false;
        }
        // 指纹库排除本会话：这一会话的旧候选马上就要被替换，拿它们比对会把新结果整批误判为重复。
        List<String> knownFp = dedup ? existingFingerprints(s.getRoomId(), s.getId()) : new ArrayList<>();

        // 旧候选的清除放在"新片段即将入库"这一刻，而不是分析之前：
        // 分析要跑几分钟，先删再算的话一旦这期间进程被关/线程被中断，旧片段没了、新片段也没进来，
        // 这个会话就直接退回"零片段"（实测 852/820/821 都这么丢过）。
        dropStaleClips(s);

        List<Clip> accepted = new ArrayList<>();
        for (Clip c : clips) {
            if (dedup) {
                String fp = ClipFingerprint.compute(source, nz(c.getStartSec()), nz(c.getEndSec()));
                if (fp != null && matchesAny(fp, knownFp)) {
                    log.info("片段去重：clip[{}-{}] 与已有片段内容重复，跳过（room={} session={}）",
                            c.getStartSec(), c.getEndSec(), s.getRoomId(), s.getId());
                    continue;
                }
                c.setFingerprint(fp);
                if (fp != null) knownFp.add(fp); // 本批次内后续片段也要与之比对（录制内循环）
            }
            c.setCreatedAt(LocalDateTime.now());
            clipMapper.insert(c);
            c.setFilePath(dir + "/clips/clip_" + c.getId() + ".mp4");
            clipMapper.updateById(c);
            accepted.add(c);
            // 不再为每个片段自动生成「照搬单片段」的作品；作品统一由下方 makeReelWork 把多个精彩片段
            // 拼接成一支高光集锦成片。片段分析页仍可对任意单个片段手动「生成作品」。
        }
        // 高光集锦：real 模式下每次录制分析完成后自动产出一支选段拼接成片（时长随录制总时长自适应）
        if (realMode() && settings.getBool("reel_enabled", true)) {
            makeReelWork(s, accepted);
        }
        s.setAnalyzeStatus("DONE");
        sessionMapper.updateById(s);
        return accepted;
    }

    /** 片段去重相似度阈值：>= 此值判为同一段内容 */
    private static final double DEDUP_SIMILARITY = 0.85;

    /**
     * 重新分析前先回收该会话上一轮的候选片段：否则旧切片（往往是更早算法产出的等长铺满段）会和
     * 新结果叠在同一个列表里，既看不出这次到底选了什么，也可能被手动成片误选。
     * 只清还「没人用」的：已建过作品、已被人工标记（采纳/废弃）的片段保留。
     */
    private void dropStaleClips(RecordingSession s) {
        List<Clip> prior = clipMapper.selectList(Wrappers.<Clip>lambdaQuery()
                .eq(Clip::getSessionId, s.getId()));
        int removed = 0;
        for (Clip c : prior) {
            if (!"PENDING".equals(c.getStatus())) continue;
            long used = workMapper.selectCount(Wrappers.<Work>lambdaQuery().eq(Work::getClipId, c.getId()));
            if (used > 0) continue;
            File f = existingFile(c.getFilePath());
            if (f != null && !f.delete()) log.debug("旧片段文件未能删除: {}", f);
            clipMapper.deleteById(c.getId());
            removed++;
        }
        if (removed > 0) log.info("重新分析 session={}：清理上一轮候选片段 {} 个", s.getId(), removed);
    }

    /** 该房间历史片段的指纹（最多取最近 3000 条，覆盖轮播循环足够）；excludeSessionId 用于排除即将被替换的本会话旧候选 */
    private List<String> existingFingerprints(Long roomId, Long excludeSessionId) {
        if (roomId == null) return new ArrayList<>();
        List<Clip> prior = clipMapper.selectList(Wrappers.<Clip>lambdaQuery()
                .select(Clip::getFingerprint)
                .eq(Clip::getRoomId, roomId)
                .isNotNull(Clip::getFingerprint)
                .ne(excludeSessionId != null, Clip::getSessionId, excludeSessionId)
                .orderByDesc(Clip::getId)
                .last("limit 3000"));
        List<String> fps = new ArrayList<>(prior.size());
        for (Clip p : prior) if (p.getFingerprint() != null) fps.add(p.getFingerprint());
        return fps;
    }

    private static boolean matchesAny(String fp, List<String> known) {
        for (String k : known) {
            if (ClipFingerprint.similarity(fp, k) >= DEDUP_SIMILARITY) return true;
        }
        return false;
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
        // 集锦目标：录制/3，最短 5min、最长 20min，但短录制不能倒挂要求比原片还长的集锦
        int target = (int) Math.min(1200, Math.max(recorded / 3.0, Math.min(300, recorded * 0.6)));

        // 评分降序选段（集锦优先高分），累计到目标时长即收尾（最后一段允许略微超一点）
        List<Clip> byScore = new ArrayList<>(clips);
        byScore.sort(Comparator.comparingInt((Clip c) -> c.getScore() == null ? 0 : c.getScore()).reversed());
        List<Clip> chosen = new ArrayList<>();
        int acc = 0;
        for (Clip c : byScore) {
            if (c.getStartSec() == null || c.getEndSec() == null) continue;
            int dur = c.getEndSec() - c.getStartSec();
            if (dur <= 0) continue;
            chosen.add(c);
            acc += dur;
            if (acc >= target) break;
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
        // 集锦标题同样走三蛆风格（用分数最高的那段做语境）：这里曾经硬编码成
        // 《主播》2026/10/14 高光集锦 ——书名号+日期正是标题规则里明确禁止的，且完全绕开了 TitleGenerator。
        Clip best = byScore.get(0);
        TitleGenerator.Result reelTs = titleGenerator.suggest(best, false);
        String reelTitle = reelTs.title() != null && !reelTs.title().isBlank()
                ? reelTs.title() : shortRoomLabel(roomName) + " 高光集锦";
        Work w = new Work();
        w.setClipId(chosen.get(0).getId());
        w.setRoomId(s.getRoomId());
        w.setRoomName(s.getRoomName());
        w.setTitle(reelTitle);
        w.setDescription("");
        w.setTags(resolveTags(s.getRoomId()));
        w.setSubtitleEnabled(false);
        w.setSubtitleStyle("none");
        w.setAspectRatio("16:9");
        w.setStatus("DRAFT");
        w.setScore(topScore);
        // 建作品即写入选段总长，渲染完成后 renderDone 会用成片实测值覆盖
        w.setDurationSec(acc);
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
        enrichWorkAsync(w.getId(), best.getId()); // 与单片段一致：稍后用本地大模型换成三蛆风格标题并补简介
        log.info("高光集锦作品已创建 work={} 段={} 预计={}s（目标 {}s，录制 {}s）",
                w.getId(), chosen.size(), acc, target, recorded);
    }

    /** 房间名做标题保底：剥掉营销尾巴，别把"XX官方直播间"整串塞进标题 */
    private static String shortRoomLabel(String roomName) {
        String r = roomName.replaceAll("(官方直播间|官方|录播|回放|直播中|直播间).*$", "").trim();
        return r.isEmpty() ? "直播" : (r.length() > 8 ? r.substring(0, 8) : r);
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
        w.setRoomId(clip.getRoomId());
        w.setRoomName(clip.getRoomName());
        // 即时返回：标题走纯规则（不等本地模型），简介先留空，随后 enrichWorkAsync 用大模型补全
        TitleGenerator.Result ts = titleGenerator.suggest(clip, false);
        // 纯规则结果（含 generic 回退"主播+决胜时刻名场面"）本身就是三蛆风格保底，
        // 过去这里又叠了一层"房间名 2026/10/14 精彩片段 #788"——书名号外的日期与编号同样是标题规则禁的东西
        String instant = ts.title() != null && !ts.title().isBlank()
                ? ts.title() : shortRoomLabel(roomName) + " 精彩片段";
        w.setTitle(instant);
        w.setDescription("");
        w.setTags(resolveTags(clip.getRoomId()));
        w.setSubtitleEnabled(true);
        w.setSubtitleStyle("big");
        w.setAspectRatio("9:16");
        w.setStatus("DRAFT");
        w.setScore(clip.getScore());
        w.setDurationSec(clip.getDurationSec()); // 单片段作品：成片长度即片段长度（16:9 重剪后误差在秒级）
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
        String out = renderer.render(w).getOutputPath();
        w.setOutputPath(out);
        // 实测成片长度：集锦由多段拼成，界面若拿单片段时长显示会严重偏小（实测 420s 的集锦显示成 1 分钟）
        int dur = renderer.probeDurationSec(out);
        if (dur > 0) w.setDurationSec(dur);
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

    /** 优先使用房间级发布标签，为空则回退全局默认 */
    private String resolveTags(Long roomId) {
        if (roomId != null) {
            LiveRoom room = liveRoomMapper.selectById(roomId);
            if (room != null && room.getPublishTags() != null && !room.getPublishTags().isBlank()) {
                return room.getPublishTags();
            }
        }
        return settings.getStr("publish_default_tags", "直播切片,AI剪辑");
    }
}
