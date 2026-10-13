package com.aiqiepian.engine.real;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.SessionFiles;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.engine.Recorder;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.service.SettingsService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 真实录制（M1/M3）：
 * <ul>
 *   <li>B站：playUrl 取 FLV，单个 ffmpeg 持续录制子进程（不断流），配弹幕采集；</li>
 *   <li>斗鱼/虎牙：边缘节点会对匿名长连接周期性切断（虎牙尤其约 2~60s），
 *       采用「重连监管线程」——每段录制输出 segNNN.mp4，断流即取新签名重连，
 *       结束时用 concat demuxer 无损合并为最终 mp4。</li>
 * </ul>
 * 输出 media/recordings/session_{id}_{ts}.mp4；stop 优雅收尾（'q'），兜底 destroy。
 * 进程/线程句柄存内存 Map&lt;sessionId, Handle&gt;；上限时长 settings.max_record_minutes（默认 240）。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RealRecorder implements Recorder {

    private static final Logger log = LoggerFactory.getLogger(RealRecorder.class);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    /** 单次重连失败后的退避；连续失败上限（超过则判定下播/取流不可用） */
    private static final long RECONNECT_BACKOFF_MS = 1_500L;
    private static final int MAX_CONSECUTIVE_FAILS = 5;

    private final SettingsService settings;
    private final LiveRoomMapper liveRoomMapper;
    /** M2 弹幕增强通道（可选）：Cookie 齐全时随 B站录制采集弹幕，任何失败静默降级，绝不影响录制 */
    private final DanmakuCollector danmakuCollector;

    /** 取流来源：地址 + ffmpeg 需要的 UA/Referer */
    private record Source(String flvUrl, String ua, String referer) {
    }

    /** 内存录制句柄：sessionId → 录制进程/监管线程 */
    private static class Handle {
        volatile Process process;
        final Instant startedAt;
        final File file;
        final LinkedList<String> logTail;
        final boolean supervisor;
        volatile boolean stopping;
        volatile boolean finished;
        Thread thread;
        /** 监管模式下当前正在录的分段文件（结束后登记进 segments），用于实时体积探测 */
        volatile File currentSeg;
        final List<File> segments = new ArrayList<>();
        /** 成功录出的分段数（含首段）；断流重连次数 ≈ segmentCount-1 */
        volatile int segmentCount;

        Handle(Process process, Instant startedAt, File file, LinkedList<String> logTail, boolean supervisor) {
            this.process = process;
            this.startedAt = startedAt;
            this.file = file;
            this.logTail = logTail;
            this.supervisor = supervisor;
        }
    }

    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();

    @Override
    public boolean supportsRealRecording(String platform) {
        return "bilibili".equalsIgnoreCase(platform)
                || "douyu".equalsIgnoreCase(platform)
                || "huya".equalsIgnoreCase(platform);
    }

    @Override
    public void start(RecordingSession session, boolean manual) {
        LiveRoom room = liveRoomMapper.selectById(session.getRoomId());
        String platform = room == null ? null : room.getPlatform();
        if (!supportsRealRecording(platform)) {
            throw new BizException("该平台真实录制暂未支持（仅 B站/斗鱼/虎牙），本房间只做开播检测");
        }
        String roomId = resolveRoomId(platform, room);
        if (roomId == null) {
            throw new BizException("无法从房间 URL 解析直播间号");
        }
        Source first = resolveSource(platform, roomId);
        if (first == null || first.flvUrl() == null) {
            throw new BizException("获取直播流地址失败（房间可能未开播）");
        }

        int maxMinutes = Math.max(1, settings.getInt("max_record_minutes", 240));
        session.setDurationSec(maxMinutes * 60);
        session.setRecordedSec(0);
        session.setFileSizeBytes(0L);

        String dir = settings.getStr("media_dir", "./media");
        Path recDir = Path.of(dir, "recordings");
        File out;
        try {
            Files.createDirectories(recDir);
            out = recDir.resolve("session_" + session.getId() + "_" + System.currentTimeMillis() + ".mp4")
                    .toFile();
        } catch (Exception e) {
            throw new BizException("创建录制目录失败: " + e.getMessage());
        }
        session.setFilePath(out.getAbsolutePath());

        boolean supervised = !"bilibili".equalsIgnoreCase(platform);
        try {
            if (!supervised) {
                Process p = launchSegment(first, out, null);
                LinkedList<String> tail = FfmpegSupport.consumeOutput(p, 30);
                handles.put(session.getId(), new Handle(p, Instant.now(), out, tail, false));
                log.info("真实录制已启动(B站单进程) session={} room={} file={}", session.getId(), roomId, out);
                try {
                    danmakuCollector.begin(roomId, session.getId());
                } catch (Exception de) {
                    log.warn("弹幕采集挂接失败（降级为纯录制）: {}", de.toString());
                }
            } else {
                Handle h = new Handle(null, Instant.now(), out, new LinkedList<>(), true);
                handles.put(session.getId(), h);
                Thread t = new Thread(
                        () -> supervise(session.getId(), platform, roomId, first, out, h, maxMinutes * 60L),
                        "rec-supervisor-" + session.getId());
                t.setDaemon(true);
                h.thread = t;
                t.start();
                log.info("真实录制已启动({}重连监管) session={} room={} file={}", platform, session.getId(), roomId, out);
            }
        } catch (Exception e) {
            handles.remove(session.getId());
            throw new BizException("启动 ffmpeg 录制失败: " + e.getMessage());
        }
    }

    /** 重连监管线程：循环取流→录段→断流重连，直至停止/时长上限/连续失败超限；结束合并分段 */
    private void supervise(Long sessionId, String platform, String roomId, Source first,
                           File out, Handle h, long budgetSec) {
        Source src = first;
        int segIndex = 0;
        int consecutiveFails = 0;
        long deadline = System.currentTimeMillis() + budgetSec * 1000L;
        try {
            while (!h.stopping && System.currentTimeMillis() < deadline) {
                if (src == null) {
                    if (++consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                        log.warn("重连监管：连续取流失败 {} 次，判定下播/不可用，结束 session={}", consecutiveFails, sessionId);
                        break;
                    }
                    sleep(RECONNECT_BACKOFF_MS);
                    src = resolveSource(platform, roomId);
                    continue;
                }
                File seg = new File(out.getAbsolutePath() + ".seg" + String.format("%03d", segIndex++) + ".mp4");
                h.currentSeg = seg;
                Process p = null;
                try {
                    p = launchSegment(src, seg, h.logTail);
                    h.process = p;
                    long remainMs = deadline - System.currentTimeMillis();
                    long waited = 0;
                    while (p.isAlive() && !h.stopping && waited < remainMs) {
                        p.waitFor(Math.min(1000, Math.max(1, remainMs - waited)), TimeUnit.MILLISECONDS);
                        waited += 1000;
                    }
                    if (p.isAlive()) gracefulQuit(p, h.logTail);
                    long segBytes = seg.isFile() ? seg.length() : 0;
                    h.currentSeg = null;
                    if (segBytes > 0) {
                        synchronized (h.segments) {
                            h.segments.add(seg);
                        }
                        h.segmentCount++;
                        consecutiveFails = 0;
                    } else {
                        // 段无数据：视为一次失败，稍后取新签名重连
                        consecutiveFails++;
                        if (seg.isFile() && !seg.delete()) seg.deleteOnExit();
                    }
                } catch (Exception e) {
                    log.warn("重连监管：段录制异常 session={}: {}", sessionId, e.toString());
                    if (p != null) p.destroyForcibly();
                    consecutiveFails++;
                }
                if (h.stopping || System.currentTimeMillis() >= deadline) break;
                if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                    log.warn("重连监管：断流后连续重连失败 {} 次，结束 session={}", consecutiveFails, sessionId);
                    break;
                }
                // 断流重连：取新签名
                sleep(RECONNECT_BACKOFF_MS);
                src = resolveSource(platform, roomId);
            }
        } finally {
            mergeSegments(out, h);
            h.finished = true;
            log.info("重连监管结束 session={} 段数={} 最终文件={} 存在={}",
                    sessionId, h.segments.size(), out, out.isFile());
        }
    }

    /** 拉起一段 ffmpeg 录制进程（不限制时长，随断流/停止退出），分片 mp4 保证异常退出仍可播 */
    private Process launchSegment(Source src, File outFile, LinkedList<String> sharedTail) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg",
                "-hide_banner", "-loglevel", "warning",
                "-user_agent", src.ua(),
                "-referer", src.referer(),
                "-rw_timeout", "15000000",
                "-i", src.flvUrl(),
                "-c", "copy", "-bsf:a", "aac_adtstoasc",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                "-y", outFile.getAbsolutePath()));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (sharedTail != null) {
            FfmpegSupport.consumeOutputTo(p, sharedTail, 30);
        } else {
            FfmpegSupport.consumeOutput(p, 30);
        }
        return p;
    }

    /** concat demuxer 无损合并所有分段到最终文件；单段直接改名；全失败取最大段兜底 */
    private void mergeSegments(File out, Handle h) {
        List<File> segs = new ArrayList<>(h.segments);
        h.segments.clear();
        if (segs.isEmpty()) return;
        // 单段改名失败时把段留在句柄里，行为与旧实现一致（便于 stop 后按段统计体积）
        if (!mergeInto(out, segs) && segs.size() == 1) h.segments.add(segs.get(0));
    }

    /** 该最终文件在录制目录下的所有分段，按 segNNN 升序 */
    static List<File> segmentsOf(File finalFile) {
        return SessionFiles.segmentsOf(finalFile);
    }

    /** 会话当前真正可读的文件（最终文件或回退到最新分段） */
    public static File playableFile(String filePath) {
        return SessionFiles.playable(filePath);
    }

    /**
     * 把分段合并进 out：单段直接改名，多段走 concat demuxer 无损拼接，失败退到最大段。
     * 供录制收尾与启动时的遗留分片补救共用。返回 out 是否真的可用。
     */
    static boolean mergeInto(File out, List<File> segs) {
        List<File> present = new ArrayList<>();
        for (File s : segs) if (s.isFile() && s.length() > 0) present.add(s);
        if (present.isEmpty()) return false;
        if (present.size() == 1) {
            File only = present.get(0);
            if (out.isFile() && !out.delete()) out.deleteOnExit();
            if (only.renameTo(out)) return out.isFile();
            try {
                Files.move(only.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return out.isFile();
            } catch (Exception e) {
                log.warn("单段改名失败: {}", e.toString());
                return false;
            }
        }
        try {
            File list = new File(out.getAbsolutePath() + ".list.txt");
            StringBuilder sb = new StringBuilder();
            for (File s : present) {
                sb.append("file '").append(s.getAbsolutePath().replace("'", "'\\''")).append("'\n");
            }
            Files.writeString(list.toPath(), sb.toString(), StandardCharsets.UTF_8);
            int code = FfmpegSupport.run(List.of("ffmpeg",
                    "-hide_banner", "-loglevel", "warning",
                    "-f", "concat", "-safe", "0", "-i", list.getAbsolutePath(),
                    "-c", "copy",
                    "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                    "-y", out.getAbsolutePath()), 600, null);
            list.delete();
            if (code == 0 && out.isFile() && out.length() > 0) {
                for (File s : present) if (!s.delete()) s.deleteOnExit();
                return true;
            }
            log.warn("合并分段失败(code={})，回退最大段", code);
        } catch (Exception e) {
            log.warn("合并分段异常: {}", e.toString());
        }
        File best = null;
        for (File s : present) if (best == null || s.length() > best.length()) best = s;
        if (best == null) return false;
        try {
            Files.move(best.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return out.isFile();
        } catch (Exception e) {
            log.warn("兜底改名最大段失败: {}", e.toString());
            return false;
        }
    }

    @Override
    public void stop(RecordingSession session) {
        Handle h = session.getId() == null ? null : handles.remove(session.getId());
        int actualSec = -1;
        long bytes = 0L;
        if (h != null) {
            actualSec = (int) Duration.between(h.startedAt, Instant.now()).getSeconds();
            if (h.supervisor) {
                h.stopping = true;
                Process p = h.process;
                if (p != null) gracefulQuit(p, h.logTail);
                try {
                    if (h.thread != null) h.thread.join(60_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                bytes = currentBytes(h);
            } else {
                gracefulQuit(h.process, h.logTail);
                bytes = h.file.length();
            }
            try {
                danmakuCollector.end(session.getId());
            } catch (Exception de) {
                log.warn("弹幕采集关闭异常（忽略）: {}", de.toString());
            }
        } else {
            if (session.getStartedAt() != null) {
                actualSec = (int) Duration.between(session.getStartedAt(), java.time.LocalDateTime.now())
                        .getSeconds();
            }
            File f = session.getFilePath() == null ? null : new File(session.getFilePath());
            bytes = f != null && f.isFile() ? f.length() : 0L;
        }
        if (actualSec >= 0) {
            int limit = session.getDurationSec() == null ? 0 : session.getDurationSec();
            if (limit <= 0 || actualSec <= limit) {
                session.setDurationSec(Math.max(0, actualSec));
            }
        }
        // recorded_sec 用最终文件的真实时长，避免斗鱼/虎牙断流导致「墙钟时长」虚高。
        File finalFile = session.getFilePath() == null ? null : new File(session.getFilePath());
        double captured = finalFile != null && finalFile.isFile() ? FfmpegSupport.probeDuration(finalFile) : -1;
        int wallSec = Math.max(0, actualSec);
        session.setRecordedSec(captured > 1 ? (int) Math.round(captured) : wallSec);
        // 断流重连次数：监管模式下 = 成功分段数-1（每断一次重连就多一段）；B站单进程恒 0
        int reconnects = (h != null && h.supervisor) ? Math.max(0, h.segmentCount - 1) : 0;
        session.setReconnectCount(reconnects);
        if (reconnects > 0) {
            log.info("录制收尾 session={} 墙钟={}s 实际捕获={}s 断流重连={}次",
                    session.getId(), wallSec, (int) captured, reconnects);
        }
        session.setFileSizeBytes(bytes);
    }

    @Override
    public int probeRecordedSec(RecordingSession session) {
        Handle h = session.getId() == null ? null : handles.get(session.getId());
        if (h != null) {
            return (int) Duration.between(h.startedAt, Instant.now()).getSeconds();
        }
        if (session.getStartedAt() != null && !"RECORDING".equals(session.getStatus())) {
            return nz(session.getRecordedSec());
        }
        if (session.getStartedAt() != null) {
            return (int) Duration.between(session.getStartedAt(), java.time.LocalDateTime.now()).getSeconds();
        }
        return -1;
    }

    @Override
    public long probeFileSizeBytes(RecordingSession session) {
        Handle h = session.getId() == null ? null : handles.get(session.getId());
        if (h != null) return currentBytes(h);
        File f = session.getFilePath() == null ? null : new File(session.getFilePath());
        return f != null && f.isFile() ? f.length() : -1;
    }

    /** 录制中体积：单进程取最终文件；监管模式取最终文件 + 已完成分段 + 当前正在录的分段 */
    private long currentBytes(Handle h) {
        long b = h.file.isFile() ? h.file.length() : 0L;
        if (h.supervisor) {
            synchronized (h.segments) {
                for (File s : h.segments) if (s.isFile()) b += s.length();
            }
            File cur = h.currentSeg;
            if (cur != null && cur.isFile()) b += cur.length();
        }
        return b;
    }

    @Override
    public boolean probeAlive(RecordingSession session) {
        Handle h = session.getId() == null ? null : handles.get(session.getId());
        if (h == null) return false;
        if (h.supervisor) return !h.finished && !h.stopping;
        Process p = h.process;
        return p != null && p.isAlive();
    }

    /** 解析某平台房间号 */
    private static String resolveRoomId(String platform, LiveRoom room) {
        if ("huya".equalsIgnoreCase(platform)) {
            return HuyaApi.resolveRoomId(HuyaApi.extractRoomToken(room));
        }
        return BilibiliApi.extractRoomId(room);
    }

    /** 按平台取直播流地址（每次重连重新解析以获取新签名） */
    private static Source resolveSource(String platform, String roomId) {
        if (roomId == null) return null;
        if ("bilibili".equalsIgnoreCase(platform)) {
            String url = BilibiliApi.fetchPlayUrl(roomId);
            return url == null ? null : new Source(url, UA, "https://live.bilibili.com/" + roomId);
        }
        if ("douyu".equalsIgnoreCase(platform)) {
            String url = DouyuApi.fetchPlayUrl(roomId);
            return url == null ? null : new Source(url, UA, "https://www.douyu.com/" + roomId);
        }
        if ("huya".equalsIgnoreCase(platform)) {
            String url = HuyaApi.fetchPlayUrl(roomId);
            return url == null ? null : new Source(url, UA, "https://www.huya.com/" + roomId);
        }
        return null;
    }

    /** 一键停止显式收尾：与 @PreDestroy 同一套逻辑，句柄已清空则什么都不做 */
    @Override
    public void drainForShutdown() {
        if (!handles.isEmpty()) shutdownAll();
    }

    /** JVM 关闭时收尾所有录制：置 stopping、杀 ffmpeg、短暂等待监管线程合并分段，避免遗留孤儿进程与未合并分段 */
    @PreDestroy
    public void shutdownAll() {
        int n = handles.size();
        for (Map.Entry<Long, Handle> e : handles.entrySet()) {
            Handle h = e.getValue();
            h.stopping = true;
            Process p = h.process;
            if (p != null && p.isAlive()) {
                gracefulQuit(p, null);
            }
            Thread t = h.thread;
            if (t != null && t.isAlive()) {
                try {
                    t.join(8_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        handles.clear();
        log.info("真实录制：应用关闭，已收尾 {} 个录制句柄", n);
    }

    /** 向 ffmpeg stdin 写 'q' 优雅退出（flush mp4 moov），最多等 8s，兜底 destroy/destroyForcibly */
    private void gracefulQuit(Process p, LinkedList<String> tail) {
        if (p == null) return;
        if (p.isAlive()) {
            try (OutputStream stdin = p.getOutputStream()) {
                stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (Exception e) {
                log.warn("发送 q 失败，改用 destroy: {}", e.toString());
            }
        }
        try {
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        if (tail != null) {
            synchronized (tail) {
                if (!tail.isEmpty()) {
                    log.info("ffmpeg 尾部日志:\n{}", String.join("\n", tail));
                    tail.clear();
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
