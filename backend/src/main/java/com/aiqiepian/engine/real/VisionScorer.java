package com.aiqiepian.engine.real;

import com.aiqiepian.service.LocalLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 画面高光增强（可选通道）：把候选窗口抽几帧丢给本机多模态模型（qwen3-vl 一类），
 * 让它用一句话说明「画面上在发生什么、值不值得剪」并给 0–100 分，作为选段的第四个信号。
 *
 * <p>开关式接入：设置项 {@code analyze_vision} 关着、或 Ollama 没起、或模型名不在本机清单里，
 * 整个通道直接跳过；单个窗口失败只让那一个窗口回退启发式，绝不抛出（ANALYZE job 不允许失败）。
 *
 * <p>刻意每窗口只抽 3 帧：本机实测 768px 帧约 1050 token/帧，一帧就把 num_ctx 吃掉四分之一，
 * 帧数再多要么爆上下文、要么把 num_ctx 拉高到 8GB 显存放不下（实测 12288 时占 ~6.4GB）。
 * 3 帧足够判断"画面上正在发生什么"。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class VisionScorer {

    private static final Logger log = LoggerFactory.getLogger(VisionScorer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final int FRAMES_PER_WINDOW = 3;
    static final int FRAME_WIDTH = 768;
    /** 单次视觉问答的读超时：小模型冷启动加载也要时间 */
    static final int CALL_TIMEOUT_SEC = 90;

    private final LocalLlmClient llm;

    /** 一个窗口的画面判读：score=0..100，subject 在放什么，highlight 值不值得剪（可为空） */
    public record Result(int score, String subject, String highlight) {
    }

    /** 增强是否可用：开关 + 服务可达 + 模型本机存在 */
    public boolean usable() {
        return llm.visionEnabled() && llm.visionModelPresent();
    }

    /**
     * 按窗口逐个判读。返回与 ranges 等长的列表，拿不到结果的元素为 null。
     *
     * @param ranges [startSec, endSec]，调用方保证落在真实时长内
     */
    public List<Result> score(File source, List<double[]> ranges, String roomName) {
        List<Result> out = new ArrayList<>();
        if (ranges.isEmpty()) return out;
        Path dir = null;
        long beganAt = System.currentTimeMillis();
        int ok = 0;
        try {
            dir = Files.createTempDirectory("aiqiepian-vl");
            for (int i = 0; i < ranges.size(); i++) {
                double[] r = ranges.get(i);
                List<Path> frames = grabFrames(source, r[0], r[1], dir, i);
                Result res = frames.size() < 2 ? null : judge(frames, r, roomName);
                out.add(res);
                if (res != null) ok++;
            }
            log.info("画面高光增强：{} 个窗口判读成功 {} 个，耗时 {}s", ranges.size(), ok,
                    (System.currentTimeMillis() - beganAt) / 1000);
        } catch (Exception e) {
            log.warn("画面高光增强整体降级（不影响出片）: {}", e.toString());
            while (out.size() < ranges.size()) out.add(null);
        } finally {
            deleteQuietly(dir);
        }
        return out;
    }

    /** 在窗口 25% / 50% / 75% 处各抽一帧（缩小到 768 宽），失败的帧跳过 */
    private List<Path> grabFrames(File source, double start, double end, Path dir, int winIndex) {
        List<Path> files = new ArrayList<>();
        double span = Math.max(1, end - start);
        for (int k = 0; k < FRAMES_PER_WINDOW; k++) {
            double at = start + span * (k + 1) / (FRAMES_PER_WINDOW + 1.0);
            Path out = dir.resolve("w" + winIndex + "_" + k + ".jpg");
            List<String> cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-ss", String.format(java.util.Locale.ROOT, "%.2f", at),
                    "-i", source.getAbsolutePath(),
                    "-frames:v", "1", "-vf", "scale=" + FRAME_WIDTH + ":-2", "-q:v", "4",
                    "-y", out.toString());
            // -ss 放在 -i 前是快速定位（不解码前面的整段），抽一帧是秒级的
            if (FfmpegSupport.run(cmd, 30, null) == 0 && Files.isReadable(out)) {
                try {
                    if (Files.size(out) > 1024) files.add(out);
                } catch (Exception ignored) {
                }
            }
        }
        return files;
    }

    private Result judge(List<Path> frames, double[] range, String roomName) {
        String prompt = "你是直播切片助手。下面 " + frames.size() + " 张图是同一场直播在 "
                + String.format("%.0f-%.0f 秒", range[0], range[1]) + " 区间内等间隔抽出的画面"
                + (roomName == null || roomName.isBlank() ? "" : "（直播间：" + roomName + "）") + "。"
                + "只输出一个 JSON 对象，不要解释、不要代码块，结构："
                + "{\"score\":0到100的整数,\"subject\":\"一句话说明画面在发生什么\",\"highlight\":\"一句话说明这段值不值得剪进高光，不值得就写平淡\"}。"
                + "score 判定标准：画面有明显事件（击杀/翻盘/抽奖开奖/嘉宾登场/大副变化/激烈操作）给 70 以上，"
                + "静态待机、黑屏、纯聊天画面给 30 以下。";
        String raw = llm.chatVision(frames, prompt, CALL_TIMEOUT_SEC);
        if (raw == null) return null;
        try {
            int head = raw.indexOf('{');
            int tail = raw.lastIndexOf('}');
            if (head < 0 || tail <= head) return null;
            JsonNode j = MAPPER.readTree(raw.substring(head, tail + 1));
            if (!j.has("score")) return null;
            int score = Math.max(0, Math.min(100, j.path("score").asInt(-1)));
            if (score < 0) return null;
            return new Result(score, oneLine(j.path("subject").asText("")), oneLine(j.path("highlight").asText("")));
        } catch (Exception e) {
            log.debug("画面判读解析失败: {} raw={}", e.toString(), raw.length() > 160 ? raw.substring(0, 160) : raw);
            return null;
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n]+", " ").replaceAll("[\"“”「」]+", "").trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        try {
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
        }
    }
}
