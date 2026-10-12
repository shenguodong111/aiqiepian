package com.aiqiepian.engine.real;

import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.Work;
import com.aiqiepian.engine.Renderer;
import com.aiqiepian.engine.model.RenderResult;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
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

/**
 * 真实成片（M1）：对录制的 mp4 用 ffmpeg 剪切 clip（-ss/-t），叠加标题条
 * （drawtext + C:/Windows/Fonts/msyh.ttc），并生成封面（中间点抽帧）。
 * 降级链：drawtext 重编码失败 → 纯 -c copy 剪切 → 直接复制源文件，保证成片必出。
 * 输出 media/works/work_{id}.mp4。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RealRenderer implements Renderer {

    private static final Logger log = LoggerFactory.getLogger(RealRenderer.class);
    /** ffmpeg filter 中 Windows 路径需转义冒号并加单引号保护：运行时值 fontfile='C\:/Windows/Fonts/msyh.ttc' */
    private static final String FONT_FILE = "'C\\:/Windows/Fonts/msyh.ttc'";

    private final SettingsService settings;
    private final ClipMapper clipMapper;
    private final RecordingSessionMapper sessionMapper;
    private final AsrClient asr;

    @Override
    public RenderResult render(Work work) {
        String dir = settings.getStr("media_dir", "./media");
        Path workDir = Path.of(dir, "works");
        Path out = workDir.resolve("work_" + work.getId() + ".mp4");
        try {
            Files.createDirectories(workDir);
        } catch (Exception e) {
            log.warn("创建 works 目录失败: {}", e.toString());
        }

        // 高光集锦：作品自带多区间裁切清单则走 concat 拼接渲染，不落到单切片分支
        if (work.getReelSegments() != null && !work.getReelSegments().isBlank()) {
            File reelSource = existing(work.getSourcePath());
            Path cover = Path.of(dir, "covers", "work_" + work.getId() + "_cover.jpg");
            boolean reelOk = reelSource != null
                    && renderReel(reelSource, work.getReelSegments(), out, cover, work.getTitle());
            if (!reelOk) log.warn("高光集锦成片失败，work={} 保持占位输出", work.getId());
            return new RenderResult(out.toString());
        }

        File source = resolveSource(work);
        if (source == null) {
            // 源文件缺失（分析引擎仍是 mock 时可能发生）：降级为 mock 风格路径，保证流程走通
            log.warn("真实成片找不到可用源文件，work={} clip={}，降级为占位输出",
                    work.getId(), work.getClipId());
            return new RenderResult(out.toString());
        }

        double len = FfmpegSupport.probeDuration(source);
        Clip clip = work.getClipId() == null ? null : clipMapper.selectById(work.getClipId());
        double start = clip == null || clip.getStartSec() == null ? 0 : Math.max(0, clip.getStartSec());
        double dur = 15;
        if (clip != null && clip.getEndSec() != null && clip.getStartSec() != null
                && clip.getEndSec() > clip.getStartSec()) {
            dur = clip.getEndSec() - clip.getStartSec();
        }
        // 按真实文件长度钳制，避免 -ss 越界导致空输出
        if (len > 0) {
            if (start >= len - 0.5) start = Math.max(0, len - Math.min(dur, 10));
            dur = Math.min(dur, len - start);
        }
        if (dur < 1) dur = Math.min(1, Math.max(0.5, len));

        // 字幕：仅当作品开启字幕且 ASR 可用时转写当前片段音频；失败/无数据 → sub=null，只保留标题条
        File sub = null;
        if (Boolean.TRUE.equals(work.getSubtitleEnabled()) && asr.enabled()) {
            Path srt = Path.of(dir, "subtitles", "work_" + work.getId() + ".srt");
            sub = asr.transcribeSegment(source, start, dur, srt);
        }

        boolean ok = renderWithTitleBar(source, out, work, start, dur, sub);
        if (!ok && sub != null) {
            // 叠加字幕失败：退回去掉字幕的标题条渲染
            ok = renderWithTitleBar(source, out, work, start, dur, null);
        }
        if (!ok) {
            ok = renderPureCopy(source, out, start, dur);
        }
        if (!ok) {
            ok = copyWhole(source, out);
        }
        if (!ok) {
            log.error("真实成片三级降级全部失败，work={}", work.getId());
            return new RenderResult(out.toString());
        }

        makeCover(out.toFile(), source, work, dir, start, dur, len);
        return new RenderResult(out.toString());
    }

    /** clip 文件（若分析真实产出过）优先，否则回到会话原始录制文件 */
    private File resolveSource(Work work) {
        if (work.getClipId() == null) return null;
        Clip clip = clipMapper.selectById(work.getClipId());
        if (clip == null) return null;
        File f = existing(clip.getFilePath());
        if (f != null) return f;
        if (clip.getSessionId() != null) {
            RecordingSession s = sessionMapper.selectById(clip.getSessionId());
            if (s != null) {
                return existing(s.getFilePath());
            }
        }
        return null;
    }

    private static File existing(String path) {
        if (path == null || path.isBlank()) return null;
        File f = new File(path);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** 首选：剪切 + drawtext 标题条（可选叠加 ASR 字幕）（重编码） */
    private boolean renderWithTitleBar(File source, Path out, Work work, double start, double dur, File sub) {
        String title = work.getTitle() == null || work.getTitle().isBlank()
                ? (work.getRoomName() == null ? "精彩片段" : work.getRoomName())
                : work.getTitle();
        StringBuilder vf = new StringBuilder("drawtext=fontfile=" + FONT_FILE
                + ":text='" + FfmpegSupport.escapeFilterValue(title) + "'"
                + ":fontsize=42:fontcolor=white:borderw=3:bordercolor=black"
                + ":box=1:boxcolor=black@0.45:boxborderw=18"
                + ":x=(w-text_w)/2:y=h*0.08");
        if (sub != null) {
            // libass 烧录字幕：路径反斜杠转斜杠、冒号转义并单引号保护；force_style 用中文字体、底部居中
            String sp = sub.getAbsolutePath().replace("\\", "/").replace(":", "\\:");
            vf.append(",subtitles=filename='").append(sp).append("'")
                    .append(":force_style='FontName=Microsoft YaHei,FontSize=15,PrimaryColour=&H00FFFFFF,")
                    .append("OutlineColour=&H80000000,BorderStyle=1,Outline=1,Shadow=0,Bold=0,MarginV=28,Alignment=2'");
        }
        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", fmt(start), "-i", source.getAbsolutePath(),
                "-t", fmt(dur),
                "-vf", vf.toString(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-movflags", "+faststart",
                "-y", out.toString()));
        StringBuilder err = new StringBuilder();
        int code = FfmpegSupport.run(cmd, 600, err);
        if (code != 0 || !outIsGood(out)) {
            log.warn("drawtext{}成片失败(exit={})，将降级。ffmpeg 输出: {}",
                    sub != null ? "+字幕" : "", code, err);
            return false;
        }
        return true;
    }

    /** 降级 1：不叠加字体的流拷贝剪切 */
    private boolean renderPureCopy(File source, Path out, double start, double dur) {
        List<String> cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", fmt(start), "-i", source.getAbsolutePath(),
                "-t", fmt(dur), "-c", "copy", "-y", out.toString());
        StringBuilder err = new StringBuilder();
        int code = FfmpegSupport.run(cmd, 300, err);
        if (code != 0 || !outIsGood(out)) {
            log.warn("纯剪切降级失败(exit={})，将整体复制。ffmpeg 输出: {}", code, err);
            return false;
        }
        return true;
    }

    /** 降级 2：整文件复制，成片必出（时间戳可能比请求长） */
    private boolean copyWhole(File source, Path out) {
        try {
            Files.copy(source.toPath(), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return outIsGood(out);
        } catch (Exception e) {
            log.warn("整体复制失败: {}", e.toString());
            return false;
        }
    }

    private static boolean outIsGood(Path out) {
        try {
            return Files.isRegularFile(out) && Files.size(out) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 封面：成片中间点抽帧；失败退回源文件抽帧 */
    private void makeCover(File workFile, File source, Work work, String dir,
                           double start, double dur, double srcLen) {
        Path cover;
        if (work.getCoverPath() != null && !work.getCoverPath().isBlank()) {
            cover = Path.of(work.getCoverPath());
        } else {
            cover = Path.of(dir, "covers", "work_" + work.getId() + "_cover.jpg");
        }
        try {
            if (cover.getParent() != null) Files.createDirectories(cover.getParent());
        } catch (Exception e) {
            log.warn("创建 covers 目录失败: {}", e.toString());
            return;
        }
        double mid = dur / 2;
        if (!ffmpegCover(workFile, cover, mid) && srcLen > 0) {
            double absMid = Math.min(Math.max(start + mid, 0), Math.max(0, srcLen - 0.5));
            ffmpegCover(source, cover, absMid);
        }
    }

    private boolean ffmpegCover(File in, Path cover, double ss) {
        List<String> cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", fmt(ss), "-i", in.getAbsolutePath(),
                "-frames:v", "1", "-q:v", "3", "-y", cover.toString());
        int code = FfmpegSupport.run(cmd, 60, new StringBuilder());
        return code == 0 && outIsGood(cover);
    }

    private static String fmt(double sec) {
        return String.format(java.util.Locale.US, "%.2f", Math.max(0, sec));
    }

    /**
     * 高光集锦：从同一原片按多个 [start,end]（秒）区间裁切，用 filter_complex trim+concat
     * 一次拼接成一支成片，并叠加标题条。单次编码，避免逐段产物再合并；成功后从集锦中段抽封面。
     * segments 格式 "start,end;start,end"。任何失败返回 false（调用方保持占位输出）。
     */
    private boolean renderReel(File source, String segments, Path out, Path cover, String title) {
        if (source == null || segments == null || segments.isBlank()) return false;
        List<int[]> segs = parseSegments(segments);
        if (segs.isEmpty()) return false;
        try {
            Files.createDirectories(out.getParent());
        } catch (Exception e) {
            log.warn("集锦输出目录创建失败: {}", e.toString());
            return false;
        }
        double srcLen = FfmpegSupport.probeDuration(source);

        StringBuilder fc = new StringBuilder();
        StringBuilder concatIn = new StringBuilder();
        int n = 0;
        double totalDur = 0;
        for (int[] seg : segs) {
            double s = Math.max(0, seg[0]);
            double e = seg[1];
            if (srcLen > 0) e = Math.min(e, srcLen);
            if (e - s < 1) continue;
            fc.append("[0:v]trim=start=").append(fmt(s)).append(":end=").append(fmt(e))
                    .append(",setpts=PTS-STARTPTS[v").append(n).append("];");
            fc.append("[0:a]atrim=start=").append(fmt(s)).append(":end=").append(fmt(e))
                    .append(",asetpts=PTS-STARTPTS[a").append(n).append("];");
            concatIn.append("[v").append(n).append("][a").append(n).append("]");
            totalDur += e - s;
            n++;
        }
        if (n == 0) return false;
        String safeTitle = title == null || title.isBlank() ? "高光集锦" : title;
        fc.append(concatIn).append("concat=n=").append(n).append(":v=1:a=1[cv][ca];");
        fc.append("[cv]drawtext=fontfile=").append(FONT_FILE)
                .append(":text='").append(FfmpegSupport.escapeFilterValue(safeTitle)).append("'")
                .append(":fontsize=42:fontcolor=white:borderw=3:bordercolor=black")
                .append(":box=1:boxcolor=black@0.45:boxborderw=18")
                .append(":x=(w-text_w)/2:y=h*0.08[outv]");

        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", source.getAbsolutePath(),
                "-filter_complex", fc.toString(),
                "-map", "[outv]", "-map", "[ca]",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-movflags", "+faststart",
                "-y", out.toString()));
        // 编码时长≈各段之和，超时按总时长放大并钳 10~60 分钟
        long timeout = Math.max(600, Math.min(3600, (long) (totalDur * 4)));
        StringBuilder err = new StringBuilder();
        int code = FfmpegSupport.run(cmd, timeout, err);
        boolean ok = code == 0 && outIsGood(out);
        if (!ok) {
            log.warn("高光集锦渲染失败(exit={}) segs={} out={}: {}", code, n, out, err);
            return false;
        }
        // 封面：从集锦本身中段抽帧（集锦已重编码，直接抽最稳）
        try {
            if (cover.getParent() != null) Files.createDirectories(cover.getParent());
            ffmpegCover(out.toFile(), cover, totalDur / 2);
        } catch (Exception e) {
            log.warn("集锦封面抽帧异常（忽略）: {}", e.toString());
        }
        log.info("高光集锦已产出: {} 段={} 时长≈{}s", out, n, (long) totalDur);
        return true;
    }

    /** 解析 "start,end;start,end" 为区间列表，忽略非法项 */
    private static List<int[]> parseSegments(String spec) {
        List<int[]> segs = new ArrayList<>();
        for (String part : spec.split(";")) {
            String[] xy = part.split(",");
            if (xy.length != 2) continue;
            try {
                int s = Integer.parseInt(xy[0].trim());
                int e = Integer.parseInt(xy[1].trim());
                if (e > s) segs.add(new int[]{s, e});
            } catch (NumberFormatException ignored) {
            }
        }
        return segs;
    }
}
