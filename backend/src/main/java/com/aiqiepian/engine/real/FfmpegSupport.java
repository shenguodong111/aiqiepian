package com.aiqiepian.engine.real;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** ffmpeg / ffprobe 子进程工具：执行、超时、输出消费与转义。 */
public final class FfmpegSupport {

    private static final Logger log = LoggerFactory.getLogger(FfmpegSupport.class);
    private static final int OUTPUT_RING = 40;

    private FfmpegSupport() {
    }

    /**
     * 同步执行一条命令，stdout/stderr 合并后全部消费（防管道阻塞），保留尾部若干行用于排错。
     * @return 退出码；超时或启动失败返回 -1
     */
    public static int run(List<String> cmd, long timeoutSec, StringBuilder tailOut) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            // 守护线程持续消费输出（防管道阻塞），主线程带超时等待
            LinkedList<String> ring = consumeOutput(p, OUTPUT_RING);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("命令超时(>{}s): {}", timeoutSec, String.join(" ", cmd));
                return -1;
            }
            int code = p.exitValue();
            if (code != 0 && tailOut != null) {
                synchronized (ring) {
                    for (String s : ring) tailOut.append(s).append('\n');
                }
            }
            return code;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            log.warn("命令执行异常 {}: {}", String.join(" ", cmd), e.toString());
            return -1;
        }
    }

    /** ffprobe 取媒体时长（秒）；失败返回 -1 */
    public static double probeDuration(File file) {
        List<String> cmd = List.of("ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.getAbsolutePath());
        StringBuilder out = new StringBuilder();
        int code = runCapturing(cmd, 30, out);
        if (code != 0) return -1;
        try {
            return Double.parseDouble(out.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 消费进程输出到环形缓冲（守护线程），进程退出后可读取尾部日志；防 stdout/stderr 管道阻塞 */
    public static LinkedList<String> consumeOutput(Process p, int maxLines) {
        LinkedList<String> ring = new LinkedList<>();
        consumeOutputTo(p, ring, maxLines);
        return ring;
    }

    /** 将进程输出追加到调用方提供的环形缓冲（重连监管的多段共用一份尾部日志） */
    public static void consumeOutputTo(Process p, LinkedList<String> ring, int maxLines) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (ring) {
                        ring.addLast(line);
                        while (ring.size() > maxLines) ring.removeFirst();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "ffmpeg-log");
        t.setDaemon(true);
        t.start();
    }

    /**
     * drawtext 值转义：ffmpeg filter 描述符中 \ : ' , [ ] { } % 需要反斜杠转义。
     */
    public static String escapeFilterValue(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c == '\\' || c == ':' || c == '\'' || c == ',' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '%') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 执行并捕获全部输出（用于 ffprobe 这类短输出命令） */
    private static int runCapturing(List<String> cmd, long timeoutSec, StringBuilder out) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            log.warn("命令执行异常 {}: {}", String.join(" ", cmd), e.toString());
            return -1;
        }
    }
}
