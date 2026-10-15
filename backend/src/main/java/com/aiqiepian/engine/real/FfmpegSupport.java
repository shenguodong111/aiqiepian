package com.aiqiepian.engine.real;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** ffmpeg / ffprobe 子进程工具：执行、超时、输出消费与转义。 */
public final class FfmpegSupport {

    private static final Logger log = LoggerFactory.getLogger(FfmpegSupport.class);
    private static final int OUTPUT_RING = 40;

    /**
     * 存活子进程登记表。被 shutdown 打断的渲染/分析线程来不及收尾时，子 ffmpeg 会活过 JVM
     * （实测遗留一个持续写盘的孤儿进程），这里统一在退出时强杀。
     *
     * <p>注意 Windows 上 {@code destroyForcibly()} 只是**发起**终止，真正送达由 JVM 的
     * process-reaper 线程异步完成；工作线程被打断后往往立刻 {@code System.exit}，reaper 来不及跑。
     * 所以条目一律保留到确认进程已死为止，退出钩子负责再杀一次并等它落地。
     */
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean stopping;

    /** 退出钩子内每个子进程的确认等待上限 */
    private static final long REAP_WAIT_SEC = 5;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> reapNow(), "ffmpeg-reaper"));
    }

    /**
     * 同步收掉所有登记在册、仍活着的子进程（ffmpeg / python 旁路 / ASR）。
     *
     * <p>既给 JVM 关闭钩子用，也给"容器关闭超时 → halt"这条路径**直接调用**：
     * {@code Runtime.halt} 不会执行 shutdown hook，只挂钩子等于给退出留了个孤儿口子
     * （实测一键停止后 douyu-danmu.py 还活着占着弹幕文件）。
     */
    public static void reapNow() {
        stopping = true;
        int killed = 0;
        for (Process p : LIVE) {
            if (!p.isAlive()) continue;
            try {
                p.destroyForcibly();
                killed++;
            } catch (Exception ignored) {
                // 子进程已自行退出
            }
        }
        // 等终止送达：调用线程活着，kill 才有机会落地
        long deadline = System.currentTimeMillis() + REAP_WAIT_SEC * 1000L;
        for (Process p : LIVE) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                p.waitFor(left, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (killed > 0) log.info("退出清理：强杀遗留子进程 {} 个", killed);
    }

    private FfmpegSupport() {
    }

    /**
     * 一键停止在关容器**之前**调用：此刻起不再接受新的子进程。
     * 被打断的渲染会走"回退再剪一遍"这类补救路径，若不禁掉就会在退出窗口里又生出一个没人管的进程。
     */
    public static void markShuttingDown() {
        stopping = true;
    }

    /**
     * 启动登记在册的子进程；进入退出流程后拒绝启动（钩子已跑完，新进程无人回收，只会变成孤儿）。
     */
    public static Process startOwned(ProcessBuilder pb) throws IOException {
        if (stopping) throw new IOException("应用正在退出，不再启动新的 ffmpeg/python 子进程");
        LIVE.removeIf(p -> !p.isAlive()); // 顺手回收已确认死亡的条目，登记表不会随分片式录制膨胀
        Process p = pb.start();
        LIVE.add(p);
        return p;
    }

    /**
     * 子进程调用方收尾：确认已退出才摘册。
     * 仍在跑（超时被杀、线程被打断）就留在册，交给下次启动清理或退出钩子，
     * 否则 {@code System.exit} 一来就再没人知道它的 PID——孤儿进程正是这么留下的。
     */
    public static void finish(Process p) {
        if (p != null && !p.isAlive()) LIVE.remove(p);
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
            p = startOwned(pb);
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
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("命令执行异常 {}: {}", String.join(" ", cmd), e.toString());
            return -1;
        } finally {
            finish(p);
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
            p = startOwned(pb);
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
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("命令执行异常 {}: {}", String.join(" ", cmd), e.toString());
            return -1;
        } finally {
            finish(p);
        }
    }
}
