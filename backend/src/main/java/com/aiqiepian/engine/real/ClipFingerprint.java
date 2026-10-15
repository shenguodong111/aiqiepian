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
 *
 * <p>v2 签名（前缀 {@value #SIG_PREFIX}）：<b>逐秒</b>抽帧算 8×8 dHash（64bit/帧），得到与片段等长的哈希序列。
 * 比对时不再按「片段内序号」硬对齐（轮播重播的起点与窗口长度都不同，按序号对齐会把同一段判成两段，
 * 实测这样算出来的相似度只有 0.75，与真正不同的片段 0.7~0.8 完全分不开），
 * 而是<b>搜索所有平移量取匹配率最高的那个</b>：同一段内容无论被从哪个时刻重新录起，
 * 总有一个平移能让两条序列逐帧对上（实测重复对 0.62~1.00，非重复对最高只有 0.29）。
 *
 * <p>旧版（纯 hex、8 帧 aHash）签名不再参与比对，由「整理重复片段」一次性重算。
 */
public final class ClipFingerprint {

    private static final Logger log = LoggerFactory.getLogger(ClipFingerprint.class);

    /** v2 签名前缀，用于与历史 v1（纯 hex）签名区分，避免两套算法互相误判 */
    public static final String SIG_PREFIX = "d1:";
    /** 抽帧间隔（秒）：1 秒栅格才能让轮播内容逐帧对上 */
    private static final int GRID_SEC = 1;
    /** 单条签名最多保留多少帧（400s）；更长的片段只按前 400s 比对 */
    private static final int MAX_FRAMES = 400;
    private static final int FRAME_SIDE = 8;
    private static final int FRAME_BYTES = FRAME_SIDE * (FRAME_SIDE + 1); // dHash 需 9×8 灰度

    /** 两帧判为相同允许的汉明距离（64bit 内） */
    private static final int FRAME_DIST = 10;
    /** 判为同一段内容：匹配帧占重叠帧的比例下限 */
    private static final double MIN_RATIO = 0.45;
    /** 判为同一段内容：匹配秒数下限，避免两条短片偶然对齐就误删 */
    private static final int MIN_MATCH_SEC = 15;

    private ClipFingerprint() {
    }

    /** 一次对齐比对的结果 */
    public record Match(int matchedSec, int overlapSec, double ratio, int shiftSec) {

        public boolean duplicate() {
            return matchedSec >= MIN_MATCH_SEC && ratio >= MIN_RATIO;
        }
    }

    private static final Match NO_MATCH = new Match(0, 0, 0, 0);

    /**
     * 计算 [startSec,endSec] 片段的逐秒 dHash 序列签名。
     *
     * @return 带 {@value #SIG_PREFIX} 前缀的十六进制签名；抽帧失败/帧数过少返回 null（按「无法判重」处理）
     */
    public static String compute(File source, int startSec, int endSec) {
        long[] frames = hashes(source, startSec, endSec);
        return frames == null ? null : SIG_PREFIX + toHex(frames);
    }

    /** 抽帧并算逐帧 dHash；帧数不足 3 或失败返回 null */
    private static long[] hashes(File source, int startSec, int endSec) {
        if (source == null || !source.isFile() || endSec <= startSec) return null;
        int dur = Math.min(endSec - startSec, MAX_FRAMES * GRID_SEC);
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", String.valueOf(startSec), "-i", source.getAbsolutePath(),
                "-t", String.valueOf(dur), "-an",
                "-vf", "fps=" + (1.0 / GRID_SEC) + ",scale=" + (FRAME_SIDE + 1) + ":" + FRAME_SIDE
                        + ":flags=area,format=gray",
                "-f", "rawvideo", "-");
        byte[] raw = runRawVideo(cmd, 120);
        if (raw == null) return null;
        int n = raw.length / FRAME_BYTES;
        if (n < 3) {
            log.info("指纹抽帧过少({})，跳过去重判定 clip窗口=[{},{}]", n, startSec, endSec);
            return null;
        }
        long[] out = new long[n];
        for (int f = 0; f < n; f++) {
            int off = f * FRAME_BYTES;
            long h = 0;
            for (int r = 0; r < FRAME_SIDE; r++) {
                int row = off + r * (FRAME_SIDE + 1);
                for (int c = 0; c < FRAME_SIDE; c++) {
                    int left = raw[row + c] & 0xFF;
                    int right = raw[row + c + 1] & 0xFF;
                    h = (h << 1) | (left > right ? 1 : 0);
                }
            }
            out[f] = h;
        }
        return out;
    }

    /** 解析签名；null 表示缺失或旧版格式（不参与比对） */
    public static long[] decode(String sig) {
        if (sig == null || !sig.startsWith(SIG_PREFIX)) return null;
        String hex = sig.substring(SIG_PREFIX.length());
        int n = hex.length() / 16;
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            try {
                out[i] = Long.parseUnsignedLong(hex.substring(i * 16, i * 16 + 16), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return n < 3 ? null : out;
    }

    /**
     * 在两条哈希序列之间搜索最优平移：返回匹配率最高的那个对齐。
     * 轮播重播的内容在两次录制里起点不同、窗口长度也不同，只有搜索平移才能把它们对上。
     */
    public static Match align(long[] a, long[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return NO_MATCH;
        Match best = NO_MATCH;
        for (int d = -(b.length - 1); d < a.length; d++) {
            int lo = Math.max(0, -d);
            int hi = Math.min(a.length, b.length - d);
            if (hi <= lo) continue;
            int hit = 0;
            for (int i = lo; i < hi; i++) {
                if (Long.bitCount(a[i] ^ b[i + d]) <= FRAME_DIST) hit++;
            }
            double ratio = (double) hit / (hi - lo);
            if (hit > best.matchedSec() || (hit == best.matchedSec() && ratio > best.ratio())) {
                best = new Match(hit, hi - lo, ratio, d * GRID_SEC);
            }
        }
        return best;
    }

    /** 便捷入口：解析两条签名并对齐；任一为旧版/缺失返回 null（调用方按「无法判重」处理） */
    public static Match alignSigs(String sigA, String sigB) {
        long[] a = decode(sigA);
        long[] b = decode(sigB);
        return (a == null || b == null) ? null : align(a, b);
    }

    private static String toHex(long[] frames) {
        StringBuilder sb = new StringBuilder(frames.length * 16);
        for (long f : frames) {
            for (int shift = 60; shift >= 0; shift -= 4) {
                sb.append(Character.forDigit((int) ((f >>> shift) & 0xF), 16));
            }
        }
        return sb.toString();
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
}
