package com.aiqiepian.engine.real;

import com.aiqiepian.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地语音 ASR（faster-whisper）：把录制片段的音频转成 SRT 字幕，供 {@link RealRenderer} 烧录。
 * 通过 ProcessBuilder 调用 {@code scripts/asr.py}，Java 侧只负责抽音频 + 落 SRT。
 * 全部可降级：未开启 / 探活失败 / python 或模型缺失 / 转写失败 → 返回 null，渲染只保留标题条。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class AsrClient {

    private static final Logger log = LoggerFactory.getLogger(AsrClient.class);

    /** 转写超时（秒）：CPU small 模型对短视频足够，长段留足余量 */
    private static final long ASR_TIMEOUT_SEC = 240;

    private final SettingsService settings;

    private volatile Boolean availableCache;
    /** 探活成功的解释器（配置项留空时逐个试 PATH 上的 python/py），进程内缓存 */
    private volatile String pythonCache;
    /** 上次探活时生效的 asr_python 值：改了设置就重新探，不用重启进程 */
    private volatile String probeKey;

    public boolean enabled() {
        return settings.getBool("asr_enabled", true);
    }

    /** 探活：有能 import faster_whisper 的 python。结果按 asr_python 设置值缓存。 */
    public boolean available() {
        if (!enabled()) return false;
        probeIfChanged();
        return Boolean.TRUE.equals(availableCache);
    }

    /**
     * 可用的 ASR 解释器：优先设置项 asr_python，其次 PATH 上的 python、py。
     * 判定标准是能否 import faster_whisper；全部失败返回 null。
     */
    private String workingPython() {
        probeIfChanged();
        return pythonCache;
    }

    private void probeIfChanged() {
        String cfg = settings.getStr("asr_python", "").trim();
        if (cfg.equals(probeKey)) return;
        synchronized (this) {
            if (cfg.equals(probeKey)) return;
            availableCache = null;
            pythonCache = null;
            List<String> candidates = new ArrayList<>();
            if (!cfg.isEmpty()) candidates.add(cfg);
            candidates.add("python");
            candidates.add("py");
            for (String cand : candidates) {
                StringBuilder err = new StringBuilder();
                int code = FfmpegSupport.run(List.of(cand, "-c", "import faster_whisper"), 60, err);
                if (code == 0) {
                    pythonCache = cand;
                    availableCache = true;
                    log.info("ASR 解释器就绪: {}", cand);
                    break;
                }
                log.info("ASR 解释器不可用（{}，exit={}）：{}", cand, code, err.toString().trim());
            }
            if (!Boolean.TRUE.equals(availableCache)) {
                availableCache = false;
                log.warn("未找到可用的 ASR 解释器（需在设置页填 asr_python，或让 PATH 上的 python 装上 faster-whisper）；渲染将只保留标题条");
            }
            probeKey = cfg;
        }
    }

    /**
     * 转写源文件 [start, start+dur] 这段音频为 SRT（时间轴相对切点后 0 起点）。
     *
     * @return 生成成功的 SRT 文件；任何前置条件不满足或失败返回 null
     */
    public File transcribeSegment(File source, double start, double dur, Path srtPath) {
        if (!available() || source == null || dur < 0.5) return null;
        Path wav = srtPath.resolveSibling(
                srtPath.getFileName().toString().replaceFirst("\\.srt$", "") + ".wav");
        try {
            if (srtPath.getParent() != null) Files.createDirectories(srtPath.getParent());

            // 1) 抽单声道 16k wav（whisper 目标采样率），只取当前片段
            List<String> extract = new ArrayList<>(List.of(
                    "ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-ss", fmt(Math.max(0, start)),
                    "-i", source.getAbsolutePath(),
                    "-t", fmt(dur),
                    "-vn", "-ac", "1", "-ar", "16000",
                    "-y", wav.toString()));
            StringBuilder exErr = new StringBuilder();
            int exCode = FfmpegSupport.run(extract, 120, exErr);
            if (exCode != 0 || !nonEmpty(wav)) {
                log.warn("ASR 抽音频失败(exit={})，仅标题条。ffmpeg: {}", exCode, exErr.toString().trim());
                return null;
            }

            // 2) python 转写 → SRT
            String py = workingPython();
            String script = settings.getStr("asr_script", defaultScript());
            List<String> cmd = new ArrayList<>(List.of(
                    py, script,
                    "--input", wav.toString(),
                    "--output", srtPath.toString(),
                    "--model", settings.getStr("asr_model", "small"),
                    "--language", settings.getStr("asr_language", "zh"),
                    "--device", settings.getStr("asr_device", "cpu"),
                    "--compute", settings.getStr("asr_compute", "int8")));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // 模型已在本地缓存：强制离线，避免运行时联网校验 hub 失败；中文按 UTF-8 读写
            pb.environment().put("HF_HUB_OFFLINE", "1");
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // 进程被强杀时读流中断，交给超时/退出码判定
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(ASR_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("ASR 转写超时(>{}s)，仅标题条", ASR_TIMEOUT_SEC);
                return null;
            }
            int code = p.exitValue();
            boolean ok = code == 0 && nonEmpty(srtPath);
            if (!ok) {
                synchronized (out) {
                    log.warn("ASR 转写失败(exit={})，仅标题条。python 输出: {}", code, out.toString().trim());
                }
                return null;
            }
            log.info("ASR 字幕已生成: {}", srtPath);
            return srtPath.toFile();
        } catch (Exception e) {
            log.warn("ASR 异常，仅标题条: {}", e.toString());
            return null;
        } finally {
            try {
                Files.deleteIfExists(wav);
            } catch (Exception ignored) {
                // 临时 wav 删除失败无副作用
            }
        }
    }

    private static String defaultScript() {
        // 相对后端工作目录（backend/）两级向上定位仓库根 scripts/asr.py
        Path root = Path.of("").toAbsolutePath().getParent();
        if (root == null) root = Path.of("").toAbsolutePath();
        return root.resolve("scripts").resolve("asr.py").toString();
    }

    /**
     * 整段录音的"主播在说话"时间轴：对 source 全量跑 faster-whisper（VAD 过滤静音），
     * 由 asr.py 输出合并后的语音窗口文件（每行 "start\tend" 秒），解析为窗口列表。
     * 供无弹幕场景的高光选段使用。任何前置条件不满足/失败 → 返回空列表（调用方降级）。
     * 注意：CPU 上长音频耗时随长度线性增长，超时按时长放大（见下）。
     *
     * @return 语音窗口列表（相对音频 0 起点，秒），失败为空
     */
    public List<double[]> speechTimeline(File source, double durationSec) {
        List<double[]> windows = new ArrayList<>();
        if (!available() || source == null || durationSec < 3) return windows;
        Path tl = null;
        try {
            Path dir = Path.of(settings.getStr("media_dir", "./media"), "subtitles");
            Files.createDirectories(dir);
            tl = Files.createTempFile(dir, "speech_", ".tsv");
            String py = workingPython();
            String script = settings.getStr("asr_script", defaultScript());
            List<String> cmd = new ArrayList<>(List.of(
                    py, script,
                    "--input", source.getAbsolutePath(),
                    "--timeline-out", tl.toString(),
                    "--gap", "2.0",
                    "--model", settings.getStr("asr_model", "small"),
                    "--language", settings.getStr("asr_language", "zh"),
                    "--device", settings.getStr("asr_device", "cpu"),
                    "--compute", settings.getStr("asr_compute", "int8")));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("HF_HUB_OFFLINE", "1");
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();
            // 消费输出避免管道阻塞（whisper 可能打印进度）
            Thread reader = new Thread(() -> {
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    while (br.readLine() != null) { /* drain */ }
                } catch (Exception ignored) {
                }
            }, "asr-timeline-out");
            reader.setDaemon(true);
            reader.start();
            // 超时按时长放大：whisper small/CPU 约 0.5~1.5x 实时，留 3x 余量，钳 10~90 分钟
            long timeout = Math.max(600, Math.min(5400, (long) (durationSec * 3)));
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("语音时间轴提取超时(>{}s, dur={}s)，跳过语音通道", timeout, (long) durationSec);
                return windows;
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(tl) || Files.size(tl) == 0) {
                log.info("语音时间轴为空或失败(exit={})，无弹幕时降级为响度+场景", p.exitValue());
                return windows;
            }
            for (String line : Files.readAllLines(tl, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        double s = Double.parseDouble(parts[0]);
                        double e = Double.parseDouble(parts[1]);
                        if (e > s) windows.add(new double[]{s, e});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            log.info("语音时间轴完成：{} 个说话窗口（dur={}s）", windows.size(), (long) durationSec);
        } catch (Exception e) {
            log.warn("语音时间轴异常，降级: {}", e.toString());
        } finally {
            if (tl != null) {
                try {
                    Files.deleteIfExists(tl);
                } catch (Exception ignored) {
                }
            }
        }
        return windows;
    }

    private static boolean nonEmpty(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fmt(double sec) {
        return String.format(java.util.Locale.US, "%.2f", Math.max(0, sec));
    }
}
