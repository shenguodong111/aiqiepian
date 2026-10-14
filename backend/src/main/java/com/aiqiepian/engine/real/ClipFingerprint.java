package com.aiqiepian.engine.real;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 片段内容指纹：用于「轮播/重复内容」去重。
 *
 * <p>斗鱼轮播房会不停重播同一段录播内容，若照常录制+分析，同一片段会被反复切片、成片。
 * 本类给每个候选片段算一个与绝对时间无关、只与画面内容相关的感知指纹：
 * 用 ffmpeg 在片段窗口内均匀抽约 8 帧，缩到 8×8 灰度，逐帧算平均哈希（aHash，64bit），
 * 拼接成签名。轮播再多次命中同一段，抽到的画面一致 → 签名一致 → 判为重复。
 *
 * <p>时长归一化（固定抽约 8 帧，而非固定帧率）使不同长度、不同录制起点的同内容仍能对齐比较。
 */
public final class ClipFingerprint {

    private static final Logger log = LoggerFactory.getLogger(ClipFingerprint.class);

    /** 目标抽帧数（每帧 8×8=64bit → 签名约 8*8=64 字节 = 128 hex 字符） */
    private static final int TARGET_FRAMES = 8;
    private static final int FRAME_SIDE = 8;
    private static final int FRAME_BYTES = FRAME_SIDE * FRAME_SIDE; // 64 灰度像素

    private ClipFingerprint() {
    }

    /**
     * 计算 [startSec,endSec] 片段的感知指纹。
     * @return 十六进制签名；抽帧失败/帧数过少返回 null（调用方按「无法判重」处理，保留片段）
     */
    public static String compute(File source, int startSec, int endSec) {
        if (source == null || !source.isFile() || endSec <= startSec) return null;
        int dur = endSec - startSec;
        // fps = 目标帧数 / 片段时长 → 均匀抽约 TARGET_FRAMES 帧
        double fps = Math.max(0.05, (double) TARGET_FRAMES / dur);
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", String.valueOf(startSec), "-i", source.getAbsolutePath(),
                "-t", String.valueOf(dur), "-an",
                "-vf", "fps=" + fps + ",scale=" + FRAME_SIDE + ":" + FRAME_SIDE + ":flags=area,format=gray",
                "-f", "rawvideo", "-");
        byte[] raw = runRawVideo(cmd, 60);
        if (raw == null) return null;
        int frames = raw.length / FRAME_BYTES;
        if (frames < 3) {
            log.info("指纹抽帧过少({})，跳过去重判定 clip窗口=[{},{}]", frames, startSec, endSec);
            return null;
        }
        ByteArrayOutputStream bits = new ByteArrayOutputStream(frames * 8);
        for (int f = 0; f < frames; f++) {
            int off = f * FRAME_BYTES;
            int mean = 0;
            for (int i = 0; i < FRAME_BYTES; i++) mean += raw[off + i] & 0xFF;
            mean /= FRAME_BYTES;
            byte[] hash = new byte[8]; // 64 bit
            for (int i = 0; i < FRAME_BYTES; i++) {
                if ((raw[off + i] & 0xFF) > mean) hash[i >> 3] |= (1 << (i & 7));
            }
            bits.write(hash, 0, 8);
        }
        return toHex(bits.toByteArray());
    }

    /**
     * 两个签名的相似度 0..1（按帧对齐取平均，逐帧 1 - 汉明距离/64）。任一为空返回 0。
     */
    public static double similarity(String hexA, String hexB) {
        if (hexA == null || hexB == null || hexA.isEmpty() || hexB.isEmpty()) return 0;
        byte[] a = fromHex(hexA);
        byte[] b = fromHex(hexB);
        int frames = Math.min(a.length, b.length) / 8;
        if (frames == 0) return 0;
        long diff = 0;
        for (int f = 0; f < frames; f++) {
            for (int by = 0; by < 8; by++) {
                diff += Integer.bitCount((a[f * 8 + by] ^ b[f * 8 + by]) & 0xFF);
            }
        }
        double perBit = (double) diff / (frames * 64.0);
        return 1.0 - perBit;
    }

    private static byte[] runRawVideo(List<String> cmd, long timeoutSec) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD); // 只保留 stdout 的裸帧数据
            p = FfmpegSupport.startOwned(pb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("指纹抽帧超时(>{}s): {}", timeoutSec, String.join(" ", cmd));
                return null;
            }
            if (p.exitValue() != 0) {
                log.warn("指纹抽帧失败 exit={} cmd={}", p.exitValue(), String.join(" ", cmd));
                return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("指纹抽帧异常 {}: {}", String.join(" ", cmd), e.toString());
            return null;
        } finally {
            FfmpegSupport.finish(p);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
