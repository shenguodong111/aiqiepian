package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        List<KnownClip> known = dedup ? existingSignatures(s.getRoomId(), s.getId()) : new ArrayList<>();

        // 旧候选的清除放在"新片段即将入库"这一刻，而不是分析之前：
        // 分析要跑几分钟，先删再算的话一旦这期间进程被关/线程被中断，旧片段没了、新片段也没进来，
        // 这个会话就直接退回"零片段"（实测 852/820/821 都这么丢过）。
        dropStaleClips(s);

        List<Clip> accepted = new ArrayList<>();
        for (Clip c : clips) {
            long[] frames = null;
            if (dedup) {
                String fp = ClipFingerprint.compute(source, nz(c.getStartSec()), nz(c.getEndSec()));
                frames = ClipFingerprint.decode(fp);
                DupHit dup = frames == null ? null : findDuplicate(frames, known);
                if (dup != null) {
                    ClipFingerprint.Match m = dup.match();
                    log.info("片段去重：clip[{}-{}] 与片段 #{}（会话 {}）是同一内容——对齐 {} 秒/重叠 {} 秒、"
                                    + "轮播平移 {} 秒，跳过（room={} session={}）",
                            c.getStartSec(), c.getEndSec(), dup.clip().clipId(), dup.clip().sessionId(),
                            m.matchedSec(), m.overlapSec(), m.shiftSec(), s.getRoomId(), s.getId());
                    continue;
                }
                c.setFingerprint(fp);
            }
            c.setCreatedAt(LocalDateTime.now());
            clipMapper.insert(c);
            c.setFilePath(dir + "/clips/clip_" + c.getId() + ".mp4");
            clipMapper.updateById(c);
            accepted.add(c);
            if (frames != null) {
                known.add(new KnownClip(c.getId(), c.getSessionId(), frames)); // 本批次内后续片段也要与之比对
            }
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

    /** 一条已入库/已收下片段的指纹（frames 为逐秒 dHash 序列） */
    private record KnownClip(Long clipId, Long sessionId, long[] frames) {
    }

    /** 命中重复：被重复的那个片段 + 对齐结果 */
    private record DupHit(KnownClip clip, ClipFingerprint.Match match) {
    }

    /** 去重时回看该房间最近多少条历史片段（轮播一圈通常几百条以内） */
    private static final int DEDUP_SCAN = 400;
    /** 一次「整理重复片段」最多扫多少条片段：每条都要抽帧，再多就要跑上几分钟 */
    private static final int SWEEP_SCAN = 600;

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

    /**
     * 该房间历史片段的 v2 指纹（最近 {@value #DEDUP_SCAN} 条，足够覆盖轮播一整圈）；
     * excludeSessionId 用于排除即将被替换的本会话旧候选。旧版签名解不开，直接跳过。
     */
    private List<KnownClip> existingSignatures(Long roomId, Long excludeSessionId) {
        if (roomId == null) return new ArrayList<>();
        List<Clip> prior = clipMapper.selectList(Wrappers.<Clip>lambdaQuery()
                .select(Clip::getId, Clip::getSessionId, Clip::getFingerprint)
                .eq(Clip::getRoomId, roomId)
                .isNotNull(Clip::getFingerprint)
                .ne(excludeSessionId != null, Clip::getSessionId, excludeSessionId)
                .orderByDesc(Clip::getId)
                .last("limit " + DEDUP_SCAN));
        List<KnownClip> out = new ArrayList<>(prior.size());
        for (Clip p : prior) {
            long[] f = ClipFingerprint.decode(p.getFingerprint());
            if (f != null) out.add(new KnownClip(p.getId(), p.getSessionId(), f));
        }
        return out;
    }

    /** 在已收片段里找与这段内容相同的那一条（取匹配秒数最高的）；没有则 null */
    private static DupHit findDuplicate(long[] frames, List<KnownClip> known) {
        DupHit best = null;
        for (KnownClip k : known) {
            ClipFingerprint.Match m = ClipFingerprint.align(frames, k.frames());
            if (!m.duplicate()) continue;
            if (best == null || m.matchedSec() > best.match().matchedSec()) best = new DupHit(k, m);
        }
        return best;
    }

    /**
     * 整理存量重复片段：按 v2 指纹重新比对一遍，把「后入库的那一条」清掉（先入库的留下，
     * 因为它可能已经进了集锦）。历史片段的签名是旧算法算的，这里顺手一次性重算。
     * 只删没人用的：已被作品引用、或已被人工采纳/废弃的片段一律保留，只在报告里说明原因。
     */
    @Override
    public Map<String, Object> dedupSweep(Long roomId, boolean apply) {
        List<Clip> clips = clipMapper.selectList(Wrappers.<Clip>lambdaQuery()
                .eq(roomId != null, Clip::getRoomId, roomId)
                .orderByAsc(Clip::getId)
                .last("limit " + SWEEP_SCAN));
        Map<Long, File> sources = new HashMap<>();
        for (Clip c : clips) {
            Long sid = c.getSessionId();
            if (sid == null || sources.containsKey(sid)) continue;
            RecordingSession s = sessionMapper.selectById(sid);
            sources.put(sid, s == null ? null : existingFile(s.getFilePath()));
        }
        // 按房间分桶比对：轮播重复只可能发生在同一个主播内部，跨房间比对既慢又会误删
        Map<Long, List<KnownClip>> kept = new HashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        int recomputed = 0, deleted = 0, protectedOn = 0, undecided = 0;
        for (Clip c : clips) {
            long[] f = ClipFingerprint.decode(c.getFingerprint());
            if (f == null) {
                File src = sources.get(c.getSessionId());
                String sig = src == null ? null
                        : ClipFingerprint.compute(src, nz(c.getStartSec()), nz(c.getEndSec()));
                f = ClipFingerprint.decode(sig);
                if (f != null) {
                    clipMapper.update(null, Wrappers.<Clip>lambdaUpdate()
                            .eq(Clip::getId, c.getId())
                            .set(Clip::getFingerprint, sig));
                    recomputed++;
                }
            }
            if (f == null) {
                undecided++; // 录像文件已不在，无从判定：原样保留
                continue;
            }
            if (c.getRoomId() == null) continue;
            List<KnownClip> roomKept = kept.computeIfAbsent(c.getRoomId(), k -> new ArrayList<>());
            DupHit dup = findDuplicate(f, roomKept);
            if (dup == null) {
                roomKept.add(new KnownClip(c.getId(), c.getSessionId(), f));
                continue;
            }
            boolean used = workMapper.selectCount(Wrappers.<Work>lambdaQuery()
                    .eq(Work::getClipId, c.getId())) > 0;
            boolean deletable = !used && "PENDING".equals(c.getStatus());
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("clip_id", c.getId());
            g.put("room_id", c.getRoomId());
            g.put("room_name", c.getRoomName());
            g.put("session_id", c.getSessionId());
            g.put("range", nz(c.getStartSec()) + "-" + nz(c.getEndSec()));
            g.put("score", c.getScore());
            g.put("duplicate_of", dup.clip().clipId());
            g.put("matched_sec", dup.match().matchedSec());
            g.put("ratio", Math.round(dup.match().ratio() * 100) / 100.0);
            g.put("deleted", false);
            g.put("reason", deletable ? "" : (used ? "已有作品引用，保留" : "状态 " + c.getStatus() + "，保留"));
            if (deletable) {
                if (apply) {
                    File cf = existingFile(c.getFilePath());
                    if (cf != null && !cf.delete()) log.warn("重复片段文件未能删除: {}", cf);
                    clipMapper.deleteById(c.getId());
                    g.put("deleted", true);
                    deleted++;
                }
            } else {
                protectedOn++;
            }
            groups.add(g);
        }
        log.info("审计整理[重复片段] room={} apply={} 扫描={} 命中重复={} 实删={} 受保护={} 无法判定={} 重算指纹={}",
                roomId, apply, clips.size(), groups.size(), deleted, protectedOn, undecided, recomputed);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scanned", clips.size());
        m.put("duplicates", groups.size());
        m.put("deleted", deleted);
        m.put("protected", protectedOn);
        m.put("undecided", undecided);
        m.put("recomputed", recomputed);
        m.put("apply", apply);
        m.put("groups", groups);
        return m;
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
        // clip_id 记分数最高的那段（与手工拼接一致）：渲染前的标题/简介精修就是拿它当语境
        w.setClipId(best.getId());
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
        // 标题/简介的大模型精修放在渲染前做（见 renderDone）：封面合成在渲染里，标题必须先定稿
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
        return w;
    }

    /**
     * 手工拼接同一场录像的多个片段：与自动集锦走同一条产物路径（reel_segments → 逐段切段 concat），
     * 差别只在于选哪几段由人决定，所以不再按分数挑段、也不卡目标时长。
     */
    @Override
    public Work makeStitchWork(List<Clip> clips) {
        Clip first = clips.get(0);
        RecordingSession s = first.getSessionId() == null ? null : sessionMapper.selectById(first.getSessionId());
        if (s == null) throw new BizException("这些片段所属的录制会话不存在");
        File source = existingFile(s.getFilePath());
        if (source == null) throw new BizException("该录像的原始文件已不在磁盘上，无法拼接：" + s.getFilePath());

        List<Clip> ordered = new ArrayList<>(clips);
        ordered.sort(Comparator.comparingInt(c -> c.getStartSec() == null ? 0 : c.getStartSec()));
        StringBuilder spec = new StringBuilder();
        int acc = 0;
        for (Clip c : ordered) {
            if (spec.length() > 0) spec.append(';');
            spec.append(c.getStartSec()).append(',').append(c.getEndSec());
            acc += c.getEndSec() - c.getStartSec();
        }
        Clip best = ordered.stream()
                .max(Comparator.comparingInt(c -> c.getScore() == null ? 0 : c.getScore()))
                .orElse(ordered.get(0));
        int topScore = best.getScore() == null ? 0 : best.getScore();

        String roomName = s.getRoomName() != null && !s.getRoomName().isBlank() ? s.getRoomName() : "直播";
        TitleGenerator.Result ts = titleGenerator.suggest(best, false);
        String title = ts.title() != null && !ts.title().isBlank()
                ? ts.title() : shortRoomLabel(roomName) + " 精选拼接";

        Work w = new Work();
        w.setClipId(best.getId());          // 标题/封面/简介精修都以分数最高的那段为语境
        w.setRoomId(s.getRoomId());
        w.setRoomName(s.getRoomName());
        w.setTitle(title);
        w.setDescription("");
        w.setTags(resolveTags(s.getRoomId()));
        w.setSubtitleEnabled(false);        // 多段拼接保持画面连贯，与自动集锦一致不逐句烧字幕
        w.setSubtitleStyle("none");
        w.setAspectRatio("16:9");
        w.setStatus("DRAFT");
        w.setScore(topScore);
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
        log.info("手工拼接作品已创建 work={} 段={} 预计={}s session={}", w.getId(), ordered.size(), acc, s.getId());
        return w;
    }

    /**
     * ai_mode=local 时用大模型精修标题 + 生成投稿简介，只定向更新 title/description（不碰状态/产物）。
     *
     * <p>必须在渲染<b>之前</p>跑完：封面文字是渲染时叠上去的，标题晚改就成了两套。过去这一句挂在
     * 建作品之后异步执行，渲染几分钟里谁先谁后全凭运气，实测就出现过「视频里烧的是规则标题、
     * 稿件标题却是大模型标题」的两套标题。人工改过标题的作品（title_edited）不再精修，
     * 免得把用户定的名字换回去。
     */
    private void enrichWork(long workId, Long clipId) {
        if (!"local".equals(settings.getStr("ai_mode", "mock")) || clipId == null) return;
        try {
            Clip clip = clipMapper.selectById(clipId);
            Work cur = workMapper.selectById(workId);
            if (clip == null || cur == null || !PRE_PUBLISH.contains(cur.getStatus())) return;
            boolean lockTitle = Boolean.TRUE.equals(cur.getTitleEdited());
            TitleGenerator.Result refined = titleGenerator.suggest(clip, true);
            String newTitle = lockTitle || "generic".equals(refined.kind())
                    || refined.title() == null || refined.title().isBlank()
                    ? cur.getTitle() : refined.title();
            String aiDesc = titleGenerator.generateDescription(clip, newTitle);
            LambdaUpdateWrapper<Work> upd = Wrappers.<Work>lambdaUpdate()
                    .eq(Work::getId, workId)
                    .set(Work::getUpdatedAt, LocalDateTime.now());
            boolean changed = false;
            if (!newTitle.equals(cur.getTitle())) {
                upd.set(Work::getTitle, newTitle);
                changed = true;
            }
            if (aiDesc != null && !aiDesc.isBlank()) {
                upd.set(Work::getDescription, aiDesc);
                changed = true;
            }
            if (changed) workMapper.update(null, upd);
        } catch (Exception e) {
            log.warn("作品渲染前补全标题/简介失败 work={}: {}", workId, e.toString());
        }
    }

    @Override
    public void renderDone(Work w) {
        // 先把标题定稿再渲染：成片画面里烧的就是这个标题，两边必须是同一个值
        enrichWork(w.getId(), w.getClipId());
        Work fresh = workMapper.selectById(w.getId());
        if (fresh != null) w.setTitle(fresh.getTitle());
        String out = renderer.render(w).getOutputPath();
        // 渲染器在源录制缺失/剪辑失败时只会返回一个"占位路径"，磁盘上其实没有文件。
        // 以前照样标 READY、job 报成功，于是界面显示"待审片"却永远播不出东西（实测 work=1016 的源录像被删过）。
        if (realMode() && existingFile(out) == null) {
            workMapper.update(null, Wrappers.<Work>lambdaUpdate()
                    .eq(Work::getId, w.getId())
                    .set(Work::getStatus, "DRAFT")
                    .set(Work::getUpdatedAt, LocalDateTime.now()));
            throw new BizException("成片未产出文件：这一支要剪的源录制已不在磁盘上（素材库里删过这场录像），"
                    + "作品已退回草稿，请换一段还有录像的片段重新生成");
        }
        // 实测成片长度：集锦由多段拼成，界面若拿单片段时长显示会严重偏小（实测 420s 的集锦显示成 1 分钟）
        int dur = renderer.probeDurationSec(out);
        LocalDateTime now = LocalDateTime.now();
        // 只写自己这几列，别用 updateById 回写整行：渲染要吃掉几分钟，这期间用户可能已经在审片页
        // 改了标题，拿渲染前读到的旧快照整行覆盖就会把改名结果打回（实测发出去的稿件标题退成了规则标题）。
        workMapper.update(null, Wrappers.<Work>lambdaUpdate()
                .eq(Work::getId, w.getId())
                .set(Work::getStatus, "READY")
                .set(Work::getUpdatedAt, now)
                .set(Work::getOutputPath, out)
                .set(dur > 0, Work::getDurationSec, dur));
        w.setStatus("READY");
        w.setUpdatedAt(now);
        w.setOutputPath(out);
        if (dur > 0) w.setDurationSec(dur);
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
