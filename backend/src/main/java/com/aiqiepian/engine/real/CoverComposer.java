package com.aiqiepian.engine.real;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 封面合成：底图（主播图片素材 / 视频截帧）+ 标题文字，产出 B站 推荐的 1920x1080。
 * 文字排版对齐常见主播封面风格：标题折成至多两行、顶部居中、亮黄字 + 粗黑描边，字号按最长一行自适应。
 * 任何失败只返回 false（不抛异常打断成片流程），由调用方降级。
 */
@Component
public class CoverComposer {

    private static final Logger log = LoggerFactory.getLogger(CoverComposer.class);

    private static final int W = 1920;
    private static final int H = 1080;
    /** ffmpeg filter 中 Windows 路径需转义冒号并加单引号保护：运行时值 fontfile='C\:/Windows/Fonts/msyh.ttc' */
    private static final String FONT_FILE = "'C\\:/Windows/Fonts/msyh.ttc'";
    /** 每行最多字符数（中日韩按字计），超出折到第二行 */
    private static final int LINE_CHARS = 12;
    private static final int MAX_LINES = 2;
    /** 优先在这些字符之后断行 */
    private static final String BREAK_AFTER = "，。、；：！？,;: ";

    /** 图片素材 + 标题：素材过小图按 16:9 居中裁满 */
    public boolean fromImage(File bg, String title, Path out) {
        if (bg == null || !bg.isFile() || bg.length() == 0) return false;
        return render(List.of("-i", bg.getAbsolutePath()), title, out);
    }

    /**
     * 视频截帧 + 标题。ss 应取未烧字的原始录制，而不是成片——
     * 成片已叠了标题条，再叠一次封面文字会重影。
     */
    public boolean fromFrame(File video, double ss, String title, Path out) {
        if (video == null || !video.isFile() || video.length() == 0) return false;
        return render(List.of("-ss", fmt(ss), "-i", video.getAbsolutePath()), title, out);
    }

    private boolean render(List<String> inputArgs, String title, Path out) {
        String vf = "scale=" + W + ":" + H + ":force_original_aspect_ratio=increase,crop=" + W + ":" + H
                + textFilters(title);
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error"));
        cmd.addAll(inputArgs);
        cmd.addAll(List.of("-frames:v", "1", "-q:v", "2", "-vf", vf, "-y", out.toString()));
        StringBuilder err = new StringBuilder();
        int code = FfmpegSupport.run(cmd, 60, err);
        boolean ok = code == 0 && exists(out);
        if (!ok) log.warn("封面合成失败(exit={}) out={}: {}", code, out, err);
        return ok;
    }

    /** 标题 → drawtext 链；无有效文字时返回空串（只出底图） */
    private static String textFilters(String title) {
        List<String> lines = wrapLines(title);
        if (lines.isEmpty()) return "";
        int maxLen = 1;
        for (String l : lines) maxLen = Math.max(maxLen, l.length());
        // 字号自适应：最长一行不超出画面 88% 宽
        int fs = Math.max(44, Math.min(112, (int) (W * 0.88 / maxLen)));
        int border = Math.max(4, fs / 12);
        StringBuilder sb = new StringBuilder();
        int y = (int) (H * 0.06);
        for (String line : lines) {
            sb.append(",drawtext=fontfile=").append(FONT_FILE)
                    .append(":text='").append(FfmpegSupport.escapeFilterValue(line)).append("'")
                    .append(":fontsize=").append(fs)
                    .append(":fontcolor=0xFFE14D:borderw=").append(border).append(":bordercolor=black")
                    .append(":x=(w-text_w)/2:y=").append(y);
            y += (int) (fs * 1.30);
        }
        return sb.toString();
    }

    /**
     * 标题折行：≤12 字保持单行；否则在中间附近找标点断成两行（找不到标点则硬切）；
     * 总长超过 24 字截断加省略号——封面不是标题的容器，宁可短也不能糊成一片。
     */
    static List<String> wrapLines(String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return List.of();
        int maxTotal = LINE_CHARS * MAX_LINES;
        if (t.length() > maxTotal) t = t.substring(0, maxTotal - 1) + "…";
        if (t.length() <= LINE_CHARS) return List.of(t);
        int mid = t.length() / 2;
        int cut = -1;
        for (int d = 0; d <= 6 && cut < 0; d++) {
            for (int i : new int[]{mid - d, mid + d}) {
                if (i > 0 && i < t.length() && BREAK_AFTER.indexOf(t.charAt(i - 1)) >= 0) {
                    cut = i;
                    break;
                }
            }
        }
        if (cut <= 0) cut = LINE_CHARS;
        List<String> out = new ArrayList<>();
        String l1 = trimEdge(t.substring(0, cut), true);
        String l2 = trimEdge(t.substring(cut), false);
        if (!l1.isEmpty()) out.add(l1);
        if (!l2.isEmpty()) out.add(l2);
        return out;
    }

    /** 断行处的标点悬在行尾（或行首）会显得拖沓，折行时丢掉 */
    private static String trimEdge(String s, boolean trailing) {
        String r = s.trim();
        while (!r.isEmpty()) {
            char c = trailing ? r.charAt(r.length() - 1) : r.charAt(0);
            if (BREAK_AFTER.indexOf(c) < 0) return r;
            r = trailing ? r.substring(0, r.length() - 1) : r.substring(1);
        }
        return r;
    }

    private static boolean exists(Path p) {
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
