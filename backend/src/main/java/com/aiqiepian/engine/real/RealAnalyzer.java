package com.aiqiepian.engine.real;

import com.aiqiepian.engine.Analyzer;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.service.LocalLlmClient;
import com.aiqiepian.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实片段分析引擎（M2）：本地音频/画面分析为主通道，弹幕 jsonl 为可选增强通道。
 * <ol>
 *   <li>响度曲线：ffmpeg -map a -af ebur128，解析 stderr 每 100ms 的 M:（瞬时响度 LKFS）；</li>
 *   <li>场景切换：ffmpeg select=gt(scene,0.35)+showinfo 取 pts_time 时间戳；</li>
 *   <li>融合评分：30–150s 自适应窗口滑动，得分 = 归一化响度峰值×权重 + 场景密度×权重
 *       （存在弹幕文件时再叠加弹幕热度），非重叠 Top 3–8 窗口产出候选（任务书 ClipCandidate，
 *       即本引擎接口约定的 Clip 实体），score 映射 60–98，ai_reason 中文给出依据；</li>
 *   <li>所有区间钳制在 ffprobe 真实时长内（同时消除 mock clip 区间超时长的遗留问题）；</li>
 *   <li>任何一步失败静默降级（空/部分结果），绝不抛出——ANALYZE job 不允许失败。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RealAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(RealAnalyzer.class);

    /** ebur128 每 100ms 行：t: 1.5 TARGET:-23 LUFS M: -24.3 / M:-120.7 */
    private static final Pattern LOUD_LINE =
            Pattern.compile("t:\\s*([0-9]+(?:\\.[0-9]+)?).*?M:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    /** showinfo 行：... pts_time:12.345 ... */
    private static final Pattern SCENE_LINE = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");
    /** 场景切换阈值 */
    static final double SCENE_THRESHOLD = 0.35;
    /** 场景探测的降采样宽度（宽=-2 保持比例）：比对用小图即可，全尺寸逐帧比对纯属浪费 */
    static final int SCENE_PROBE_WIDTH = 480;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 片段最低主播说话覆盖度：语音时间轴可用时，选出的每段至少要有这个比例是有人在讲话的 */
    static final double SPEECH_MIN_COVERAGE = 0.2;

    private final SettingsService settings;
    private final LiveRoomMapper liveRoomMapper;
    /** 可选增强：本地大模型对候选高光重排并生成自然理由；不可用时静默回退启发式 */
    private final LocalLlmClient llm;
    /** 可选增强：无弹幕时用本地 ASR 语音活动(VAD)识别"主播在说话"窗口，融入选段评分 */
    private final AsrClient asr;

    @Override
    public List<Clip> analyze(RecordingSession session) {
        List<Clip> clips = new ArrayList<>();
        try {
            File source = resolveSource(session);
            if (source == null) {
                log.warn("真实分析降级：会话 {} 无可用录制文件（filePath={}），返回空片段",
                        session.getId(), session.getFilePath());
                return clips;
            }
            double duration = FfmpegSupport.probeDuration(source);
            if (duration <= 1) {
                log.warn("真实分析降级：文件 {} 时长探测失败或过短（{}s）", source, duration);
                return clips;
            }

            List<double[]> loudness = extractLoudness(source, duration);   // [t秒, LKFS]
            List<Double> scenes = extractSceneChanges(source, duration);   // 切换时间戳
            List<DanmakuEntry> danmakuEntries = loadDanmakuEntries(session); // 全量弹幕（相对秒/uid/文本）
            List<Integer> danmakuRelSec = new ArrayList<>();               // 相对录制开始的秒；无文件为空
            for (DanmakuEntry d : danmakuEntries) danmakuRelSec.add(d.relSec);
            String anchorName = resolveAnchorName(session);

            // 无/稀疏弹幕：启用语音活动通道（主播在说话的时间窗），作为选段主信号之一
            boolean danmakuSparse = danmakuRelSec.size() * 60.0 < duration; // 平均每分钟不足 1 条即视为稀疏
            List<double[]> speech = new ArrayList<>();
            if (danmakuSparse && asr.enabled() && asr.available()) {
                speech = asr.speechTimeline(source, duration);
            }

            boolean speechGate = !speech.isEmpty();
            List<Window> windows = scoreWindows(duration, loudness, scenes, danmakuRelSec, speech);
            List<Window> picked = nonOverlapTopK(windows, duration, speechGate);
            if (speechGate) {
                long talkable = windows.stream().filter(w -> w.speechCov >= SPEECH_MIN_COVERAGE).count();
                log.info("真实分析 session={}：语音硬门槛生效，含说话窗口 {}/{}，选段 {}",
                        session.getId(), talkable, windows.size(), picked.size());
            }

            // 本地大模型增强：对候选高光重排 + 自然理由（不可用/失败静默回退启发式顺序与理由）
            boolean llmReranked = false;
            if (llm.available()) {
                llmReranked = rerankWithLlm(picked, danmakuEntries, session.getRoomName());
            }

            double maxBase = picked.stream().mapToDouble(w -> w.base).max().orElse(0);
            int idx = 0;
            for (Window w : picked) {
                idx++;
                int score = mapScore(maxBase <= 0 ? 0 : w.base / maxBase);
                Clip c = new Clip();
                c.setSessionId(session.getId());
                c.setRoomId(session.getRoomId());
                c.setRoomName(session.getRoomName());
                int start = Math.max(0, (int) Math.ceil(w.start));            // 向内取整，保证非重叠
                int end = Math.min((int) Math.floor(duration), (int) Math.floor(w.end));
                if (end - start < 5) {
                    end = Math.min((int) Math.floor(duration), start + 5);
                }
                if (end <= start) {
                    continue; // 取整后无效区间（极短文件边缘），跳过
                }
                c.setStartSec(start);
                c.setEndSec(end);
                c.setDurationSec(end - start);
                c.setScore(score);
                c.setAiReason(llmReranked && w.llmReason != null && !w.llmReason.isBlank()
                        ? w.llmReason
                        : buildReason(idx, w, score, !danmakuRelSec.isEmpty()));
                c.setDanmakuHeat((int) Math.min(Integer.MAX_VALUE, w.heat));
                applyDanmakuStats(c, danmakuEntries, anchorName);
                c.setStatus("PENDING");
                clips.add(c);
            }
            log.info("真实分析完成 session={} 时长={}s 响度点={} 场景点={} 弹幕命中={} 片段数={}",
                    session.getId(), String.format("%.1f", duration),
                    loudness.size(), scenes.size(), danmakuRelSec.size(), clips.size());
        } catch (Exception e) {
            log.warn("真实分析异常，降级返回已产出片段（{} 个）: {}", clips.size(), e.toString());
        }
        return clips;
    }

    // ---------- 主通道 a：响度曲线（ebur128 瞬时响度 M:） ----------


    List<double[]> extractLoudness(File source, double duration) {
        List<String> cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "info",
                "-i", source.getAbsolutePath(),
                "-map", "a", "-af", "ebur128", "-f", "null", "-");
        List<double[]> series = new ArrayList<>();
        int code = runStreaming(cmd, timeoutSec(duration), line -> {
            Matcher m = LOUD_LINE.matcher(line);
            if (m.find()) {
                try {
                    double t = Double.parseDouble(m.group(1));
                    double lufs = Double.parseDouble(m.group(2));
                    if (lufs > -100) { // ebur128 静音初值 -120.7，剔除
                        series.add(new double[]{t, lufs});
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        });
        if (series.isEmpty()) {
            log.warn("响度曲线提取为空(exit={}) file={}，将仅用场景通道", code, source);
        }
        return series;
    }

    // ---------- 主通道 b：场景切换点 ----------

    List<Double> extractSceneChanges(File source, double duration) {
        // 先降帧到 3fps 并缩到 480 宽再做场景比对：直播切片只需要知道"哪里发生了切换"，
        // 逐帧全分辨率比对要把整片解码一遍（40 分钟的录制实测要多跑十几分钟）。
        // 采样后 showinfo 的 pts_time 落在 1/3 秒网格上，作为选段定位精度足够。
        String vf = "fps=3,scale=" + SCENE_PROBE_WIDTH + ":-2,select=gt(scene\\," + SCENE_THRESHOLD + "),showinfo";
        List<String> primary = List.of("ffmpeg", "-hide_banner", "-loglevel", "info",
                "-i", source.getAbsolutePath(), "-vf", vf, "-fps_mode", "vfr", "-an", "-f", "null", "-");
        List<Double> times = new ArrayList<>();
        int code = runStreaming(primary, timeoutSec(duration), sceneCollector(times));
        if (code != 0 && times.isEmpty()) {
            // 旧版 ffmpeg 无 -fps_mode：去掉该选项重试一次
            log.info("场景检测主方案失败(exit={})，去掉 -fps_mode 重试", code);
            List<String> fallback = List.of("ffmpeg", "-hide_banner", "-loglevel", "info",
                    "-i", source.getAbsolutePath(), "-vf", vf, "-an", "-f", "null", "-");
            runStreaming(fallback, timeoutSec(duration), sceneCollector(times));
        }
        return times;
    }

    private static Consumer<String> sceneCollector(List<Double> times) {
        return line -> {
            Matcher m = SCENE_LINE.matcher(line);
            if (m.find()) {
                try {
                    double t = Double.parseDouble(m.group(1));
                    if (times.isEmpty() || Math.abs(times.get(times.size() - 1) - t) > 0.01) {
                        times.add(t);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        };
    }

    // ---------- 弹幕增强通道：仅读取录制期间已落盘的 jsonl，缺失即跳过 ----------

    /** 单条弹幕：relSec=ts 相对会话 started_at 的秒偏移 */
    record DanmakuEntry(int relSec, long uid, String text) {
    }

    /**
     * 读取 media/{roomId}_{sessionId}_danmaku.jsonl（DanmakuCollector 落盘，ts 为 unix 秒）。
     * 文件不存在 / 会话无 startedAt / 解析失败：返回空列表，danmaku_heat 保持 0、
     * M5 统计字段留 null，纯音频画面分析照常。
     */
    List<DanmakuEntry> loadDanmakuEntries(RecordingSession session) {
        List<DanmakuEntry> rel = new ArrayList<>();
        try {
            Path dir = Path.of(settings.getStr("media_dir", "./media"));
            if (!Files.isDirectory(dir) || session.getStartedAt() == null) return rel;
            File file = null;
            try (var stream = Files.list(dir)) {
                List<Path> matches = stream
                        .filter(p -> p.getFileName().toString().endsWith("_" + session.getId() + "_danmaku.jsonl"))
                        .toList();
                if (!matches.isEmpty()) file = matches.get(0).toFile();
            }
            if (file == null) return rel;
            long startEpoch = session.getStartedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                try {
                    JsonNode node = MAPPER.readTree(line);
                    long t = node.path("ts").asLong(0);
                    if (t > 0) {
                        rel.add(new DanmakuEntry((int) (t - startEpoch),
                                node.path("uid").asLong(0),
                                node.path("text").asText("")));
                    }
                } catch (Exception ignored) {
                }
            }
            log.info("弹幕增强通道：session={} 命中 {} 条（{}）", session.getId(), rel.size(), file);
        } catch (Exception e) {
            log.warn("弹幕 jsonl 读取失败，降级为纯音频/画面分析: {}", e.toString());
        }
        return rel;
    }

    /** 主播名：房间 anchor_name 优先，回退会话 roomName */
    String resolveAnchorName(RecordingSession session) {
        try {
            LiveRoom room = session.getRoomId() == null ? null : liveRoomMapper.selectById(session.getRoomId());
            if (room != null && room.getAnchorName() != null && !room.getAnchorName().isBlank()) {
                return room.getAnchorName();
            }
        } catch (Exception e) {
            log.debug("房间主播名查询失败（忽略）: {}", e.toString());
        }
        return session.getRoomName();
    }

    /**
     * M5 弹幕统计增强（FR-P4-06）：按 clip [startSec,endSec] 过滤弹幕后计算
     * danmaku_count / danmaku_per_min / peak_per_min（60s 滚动窗最大值）/
     * unique_users（uid 去重）/ top_danmaku（词频前 5，JSON 数组字符串）/ anchor_name。
     * 无弹幕数据（jsonl 缺失）时所有字段保持 null。
     */
    void applyDanmakuStats(Clip c, List<DanmakuEntry> danmaku, String anchorName) {
        if (danmaku.isEmpty()) return;
        List<DanmakuEntry> in = new ArrayList<>();
        for (DanmakuEntry d : danmaku) {
            if (d.relSec() >= c.getStartSec() && d.relSec() <= c.getEndSec()) in.add(d);
        }
        c.setAnchorName(anchorName);
        int count = in.size();
        c.setDanmakuCount(count);
        double minutes = Math.max(1, c.getEndSec() - c.getStartSec()) / 60.0;
        c.setDanmakuPerMin((int) Math.round(count / minutes));

        int[] ts = in.stream().mapToInt(DanmakuEntry::relSec).sorted().toArray();
        int peak = 0;
        for (int i = 0; i < ts.length; i++) {  // 以每条弹幕为窗口起点，[ts, ts+60) 内计数
            int j = i;
            while (j < ts.length && ts[j] < ts[i] + 60) j++;
            peak = Math.max(peak, j - i);
        }
        c.setPeakPerMin(peak);
        c.setUniqueUsers((int) in.stream().map(DanmakuEntry::uid).distinct().count());
        c.setTopDanmaku(buildTopDanmaku(in));
    }

    /** 词频前 5：[{"t":"哈哈","c":12},...]；同频按文本升序保证稳定；超 1000 字符从尾部截断条目 */
    String buildTopDanmaku(List<DanmakuEntry> in) {
        Map<String, Long> freq = new LinkedHashMap<>();
        for (DanmakuEntry d : in) {
            if (d.text() != null && !d.text().isBlank()) freq.merge(d.text(), 1L, Long::sum);
        }
        List<Map<String, Object>> top = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("t", e.getKey());
                    m.put("c", e.getValue());
                    return m;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        try {
            String json = MAPPER.writeValueAsString(top);
            while (json.length() > 1000 && top.size() > 1) {
                top.remove(top.size() - 1);
                json = MAPPER.writeValueAsString(top);
            }
            return json.length() <= 1000 ? json : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 主通道 c：窗口融合评分 ----------

    /** 滑动窗口候选（任务书 ClipCandidate 的窗口形态，最终映射为 Clip） */
    static class Window {
        final double start;
        final double end;
        double loudMax = Double.NEGATIVE_INFINITY;
        double loudNorm;
        int sceneCount;
        double sceneNorm;
        long heat;
        double heatNorm;
        /** 窗口内"主播在说话"时长占比（语音活动覆盖），无弹幕场景的主选段信号 */
        double speechCov;
        double base;
        /** 本地大模型给出的自然理由（重排成功时填充，否则 null 回退启发式文案） */
        String llmReason;

        Window(double start, double end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 滑窗打分。步长取 1/4 窗宽：过去用半窗步长，"互不重叠"的候选必然落回首尾相接的等长切片，
     * 看起来像把整片切碎而不是挑出高光；细步长让峰值能被定位到准确位置。
     */
    List<Window> scoreWindows(double duration, List<double[]> loudness, List<Double> scenes,
                              List<Integer> danmakuRelSec, List<double[]> speech) {
        double win = pickWindow(duration);
        double step = Math.max(5, win / 4);
        boolean hasDanmaku = !danmakuRelSec.isEmpty();
        boolean hasSpeech = speech != null && !speech.isEmpty();

        List<Window> windows = new ArrayList<>();
        for (double s = 0; s + win <= duration + 0.001; s += step) {
            Window w = new Window(s, Math.min(duration, s + win));
            for (double[] p : loudness) {
                if (p[0] >= s && p[0] <= w.end && p[1] > w.loudMax) {
                    w.loudMax = p[1];
                }
            }
            if (w.loudMax > Double.NEGATIVE_INFINITY) {
                w.loudNorm = Math.max(0, Math.min(1, (w.loudMax + 70) / 70)); // -70..0 LUFS → 0..1
            }
            for (Double t : scenes) {
                if (t >= s && t <= w.end) w.sceneCount++;
            }
            for (Integer d : danmakuRelSec) {
                if (d >= s && d <= w.end) w.heat++;
            }
            if (hasSpeech) {
                double cov = 0;
                for (double[] sw : speech) {
                    double lo = Math.max(s, sw[0]);
                    double hi = Math.min(w.end, sw[1]);
                    if (hi > lo) cov += hi - lo;
                }
                w.speechCov = Math.max(0, Math.min(1, cov / Math.max(1, w.end - s)));
            }
            windows.add(w);
        }
        double maxScene = windows.stream().mapToDouble(x -> x.sceneCount).max().orElse(0);
        long maxHeat = windows.stream().mapToLong(x -> x.heat).max().orElse(0);
        for (Window w : windows) {
            w.sceneNorm = maxScene <= 0 ? 0 : w.sceneCount / maxScene;
            w.heatNorm = maxHeat <= 0 ? 0 : w.heat / (double) maxHeat;
            if (hasDanmaku) {
                w.base = 0.45 * w.loudNorm + 0.35 * w.sceneNorm + 0.2 * w.heatNorm;
            } else if (hasSpeech) {
                // 无弹幕：说话为主(0.45) + 响度(0.35) + 场景/战斗切换(0.20)
                w.base = 0.45 * w.speechCov + 0.35 * w.loudNorm + 0.20 * w.sceneNorm;
            } else {
                w.base = 0.6 * w.loudNorm + 0.4 * w.sceneNorm;
            }
        }
        return windows;
    }

    /**
     * 窗口宽度：≥90s 的文件取 duration/8 并钳制 25–60s；短样本取 duration/3（≥10s）。
     * 原来钳到 150s，一场长直播切出来的"高光"会是两分半的大块头，不像切片更像章节。
     */
    static double pickWindow(double duration) {
        if (duration >= 90) {
            return Math.max(25, Math.min(60, duration / 8));
        }
        return Math.max(10, duration / 3);
    }

    /**
     * 选段：按分数降序做非重叠贪心，段数随录制时长自适应（够拼出一支高光集锦即可，不铺满整片）。
     *
     * <p>分数下限取 {@code min(中位数, 最高分*0.7)}：分布平坦时卡在最高分之下，陡峭时约在中位。
     * 旧实现按"中位与最高分的中点"卡线，遇到整场分数接近的直播就只剩 1 段，集锦拼不出多个片段；
     * 更早的实现没有门槛还硬性凑数，于是把整片等长切碎、每段都 90+，等于没有筛选。
     *
     * @param speechGate 语音时间轴可用时要求每段都有主播说话（覆盖度 ≥ {@link #SPEECH_MIN_COVERAGE}）
     */
    List<Window> nonOverlapTopK(List<Window> windows, double duration, boolean speechGate) {
        List<Window> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingDouble((Window w) -> w.base).reversed());
        if (sorted.isEmpty()) return List.of();
        double maxBase = sorted.get(0).base;
        double median = sorted.get(sorted.size() / 2).base;
        double floor = Math.min(median, maxBase * 0.7);
        int target = targetClipCount(duration);

        List<Window> picked = new ArrayList<>();
        for (Window w : sorted) {
            if (picked.size() >= target) break;
            if (speechGate && w.speechCov < SPEECH_MIN_COVERAGE) continue;
            if (!picked.isEmpty() && w.base < floor) continue;
            boolean overlap = picked.stream().anyMatch(o -> w.start < o.end && o.start < w.end);
            if (!overlap) picked.add(w);
        }
        picked.sort(Comparator.comparingDouble((Window w) -> w.start));
        return mergeAdjacent(picked);
    }

    /**
     * 把首尾相接的窗口并成一段。滑窗步长是窗宽的 1/4，一段连续的精彩必然会被切成好几个
     * 等长且紧挨着的窗口（实测 750-810 / 810-870、69-108 / 109-147 各自成条），
     * 片段列表里就是好几条"看起来一模一样"的等长片段。它们描述的是同一段高光，
     * 合并后端数更少、每段更完整，集锦也不会再把同一段连续画面拆成两刀。
     *
     * @param asc 已按 start 升序、互不重叠的窗口
     */
    static List<Window> mergeAdjacent(List<Window> asc) {
        if (asc.size() < 2) return asc;
        List<Window> out = new ArrayList<>();
        Window cur = asc.get(0);
        for (Window nx : asc.subList(1, asc.size())) {
            // 允许的"看起来还是同一段"的最大间隔：窗宽的一成，下限 5 秒
            double maxGap = Math.max(5, (cur.end - cur.start) * 0.1);
            if (nx.start - cur.end > maxGap) {
                out.add(cur);
                cur = nx;
                continue;
            }
            Window m = new Window(cur.start, Math.max(cur.end, nx.end));
            m.loudMax = Math.max(cur.loudMax, nx.loudMax);
            m.loudNorm = Math.max(cur.loudNorm, nx.loudNorm);
            m.sceneCount = cur.sceneCount + nx.sceneCount;
            m.sceneNorm = Math.max(cur.sceneNorm, nx.sceneNorm);
            m.heat = cur.heat + nx.heat;          // 区间互不重叠，弹幕数按真实跨度累加
            m.heatNorm = Math.max(cur.heatNorm, nx.heatNorm);
            m.speechCov = Math.max(cur.speechCov, nx.speechCov);
            m.base = Math.max(cur.base, nx.base); // 分数取峰值：合并不是把精彩摊薄
            m.llmReason = cur.llmReason != null ? cur.llmReason : nx.llmReason;
            cur = m;
        }
        out.add(cur);
        return out;
    }

    /**
     * 目标段数：让选出的片段总时长约等于集锦目标时长（与 PipelineServiceImpl.makeReelWork 同源——
     * 集锦目标 = clamp(录制/3, 最短5min, 最长20min)，但不超过录制本身的大部分）。
     */
    static int targetClipCount(double duration) {
        double win = pickWindow(duration);
        double reel = Math.min(Math.max(duration / 3.0, Math.min(300, duration * 0.6)), 1200);
        return (int) Math.max(1, Math.min(16, Math.ceil(reel / win)));
    }

    /** 归一化得分映射到 60–98 */
    static int mapScore(double rel) {
        int score = 60 + (int) Math.round(38 * Math.max(0, Math.min(1, rel)));
        return Math.max(60, Math.min(98, score));
    }

    /** 中文分析依据：写清评分组成的客观事实 */
    static String buildReason(int idx, Window w, int score, boolean danmakuEnabled) {
        List<String> parts = new ArrayList<>();
        if (w.speechCov > 0.15) {
            parts.add(String.format("主播解说密集（约 %.0f%% 时间在说话）", w.speechCov * 100));
        }
        if (w.loudNorm > 0.01) {
            parts.add(String.format("音频能量峰值（瞬时响度 %.1f LUFS）", w.loudMax));
        }
        if (w.sceneCount > 0) {
            parts.add(String.format("场景切换密集（窗口内 %d 次）", w.sceneCount));
        }
        if (w.heat > 0) {
            parts.add(String.format("弹幕互动活跃（窗口内 %d 条）", w.heat));
        }
        if (parts.isEmpty()) {
            parts.add(String.format("相对全片综合能量居前（区间 %.0f-%.0f 秒）", w.start, w.end));
        }
        if (!danmakuEnabled && parts.size() == 1) {
            parts.add("弹幕通道未启用（无 Cookie 或未采集），热度按 0 计");
        }
        return "第" + idx + "候选：" + String.join(" + ", parts) + "，综合评分 " + score + "。";
    }

    // ---------- 本地大模型增强：候选高光重排 + 自然理由 ----------

    /**
     * 把候选窗口的客观信号（响度峰值/场景密度/弹幕热度+高频弹幕）交给本地大模型，
     * 请其按精彩度重排并逐条给 ≤20 字中文理由。成功返回 true（picked 已按模型顺序重排、
     * 每个 Window.llmReason 已填）；解析失败或输出非法返回 false（调用方回退启发式）。
     */
    boolean rerankWithLlm(List<Window> picked, List<DanmakuEntry> dm, String title) {
        if (picked.isEmpty()) return false;
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < picked.size(); i++) {
            Window w = picked.get(i);
            double loud = w.loudMax == Double.NEGATIVE_INFINITY ? -99 : w.loudMax;
            list.append("编号").append(i).append("：")
                    .append(String.format("%.0f-%.0f秒", w.start, w.end))
                    .append("，峰值响度").append(String.format("%.1f", loud)).append("LUFS")
                    .append("，场景切换").append(w.sceneCount).append("次")
                    .append("，弹幕").append(w.heat).append("条");
            String top = topWords(w, dm, 3);
            if (!top.isEmpty()) list.append("，高频弹幕[").append(top).append("]");
            list.append('\n');
        }
        String prompt = "你是直播高光剪辑助手。依据音频响度、场景切换密度、弹幕互动热度，对候选高光片段按精彩程度从高到低排序，"
                + "并为每条写不超过15字的中文精彩理由。只输出 JSON，结构为 "
                + "{\"items\":[{\"index\":编号,\"reason\":\"理由\"}]}，items 按精彩度先后排列，编号为候选编号。\n\n"
                + "直播标题：" + nz(title) + "\n候选片段（每行一个）：\n" + list;
        String out = llm.chatJson(prompt, 60);
        if (out == null) return false;
        try {
            JsonNode arr = findArray(MAPPER.readTree(out));
            if (arr == null || !arr.isArray() || arr.isEmpty()) return false;
            Map<Integer, String> reasonByIndex = new LinkedHashMap<>();
            List<Window> ordered = new ArrayList<>();
            for (JsonNode n : arr) {
                int index = n.path("index").asInt(-1);
                if (index < 0 || index >= picked.size() || reasonByIndex.containsKey(index)) continue;
                String reason = n.path("reason").asText("").replaceAll("[\\r\\n]+", " ").replaceAll("[\"“”「」]+", "").trim();
                if (reason.length() > 40) reason = reason.substring(0, 40);
                Window w = picked.get(index);
                w.llmReason = reason.isEmpty() ? null : reason;
                reasonByIndex.put(index, w.llmReason);
                ordered.add(w);
            }
            if (ordered.isEmpty()) return false;
            for (int i = 0; i < picked.size(); i++) {   // 模型漏掉的编号补到末尾，保证片段不丢
                if (!reasonByIndex.containsKey(i)) ordered.add(picked.get(i));
            }
            picked.clear();
            picked.addAll(ordered);
            log.info("本地大模型高光重排完成：{} 个候选已重排", picked.size());
            return true;
        } catch (Exception e) {
            log.warn("大模型重排解析失败，回退启发式: {} raw={}", e.toString(), truncate(out));
            return false;
        }
    }

    /** 从模型 JSON 输出里取出候选数组：兼容顶层数组，或对象包裹（如 Ollama 的 {"data":[...]} / {"items":[...]}） */
    private static JsonNode findArray(JsonNode root) {
        if (root == null) return null;
        if (root.isArray()) return root;
        if (root.isObject()) {
            java.util.Iterator<JsonNode> it = root.elements();
            while (it.hasNext()) {
                JsonNode v = it.next();
                if (v.isArray()) return v;
            }
        }
        return null;
    }

    /** 窗口 [start,end] 内高频弹幕词前 k 个（按频次降序，逗号连接） */
    private static String topWords(Window w, List<DanmakuEntry> dm, int k) {
        Map<String, Long> freq = new LinkedHashMap<>();
        for (DanmakuEntry d : dm) {
            if (d.relSec() >= w.start && d.relSec() <= w.end && d.text() != null && !d.text().isBlank()) {
                freq.merge(d.text().trim(), 1L, Long::sum);
            }
        }
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(k)
                .map(e -> e.getKey().length() > 12 ? e.getKey().substring(0, 12) : e.getKey())
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    // ---------- 工具 ----------

    private static File resolveSource(RecordingSession session) {
        if (session.getFilePath() == null || session.getFilePath().isBlank()) return null;
        File f = new File(session.getFilePath());
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** 分析超时随文件长度伸缩：至少 120s，最多 15 分钟 */
    private static long timeoutSec(double duration) {
        return Math.max(120, Math.min(900, (long) (duration * 2)));
    }

    /** 全量流式消费 ffmpeg 输出（ebur128 每 100ms 一行，长文件可达数十万行，不能只留尾部） */
    private int runStreaming(List<String> cmd, long timeoutSec, Consumer<String> onLine) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = FfmpegSupport.startOwned(pb);
            final Process fp = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        onLine.accept(line);
                    }
                } catch (Exception ignored) {
                }
            }, "real-analyze-out");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("分析命令超时(>{}s): {}", timeoutSec, String.join(" ", cmd));
                return -1;
            }
            reader.join(5000);
            return p.exitValue();
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("分析命令执行异常 {}: {}", String.join(" ", cmd), e.toString());
            return -1;
        } finally {
            FfmpegSupport.finish(p);
        }
    }
}
