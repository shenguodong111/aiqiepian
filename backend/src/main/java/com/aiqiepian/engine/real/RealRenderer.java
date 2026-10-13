package com.aiqiepian.engine.real;

import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.CoverAsset;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.Work;
import com.aiqiepian.engine.Renderer;
import com.aiqiepian.engine.model.RenderResult;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.CoverAssetMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
 * （drawtext + C:/Windows/Fonts/msyh.ttc），并生成封面。
 * 封面优先用房间配置的主播图片素材（CoverComposer 叠标题文字），未配置素材则截源视频帧再加字。
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
    private final CoverAssetMapper coverAssetMapper;
    private final AsrClient asr;
    private final CoverComposer coverComposer;

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
            boolean reelOk = reelSource != null && renderReel(reelSource, work.getReelSegments(), out, work.getTitle());
            if (reelOk) {
                // 封面取第一段高亮的中间点：从未烧字的原始录制取帧，文字统一交给 CoverComposer 叠
                List<int[]> segs = parseSegments(work.getReelSegments());
                int[] first = segs.isEmpty() ? new int[]{0, 6} : segs.get(0);
                makeCover(out.toFile(), reelSource, work, dir, first[0], first[1] - first[0],
                        FfmpegSupport.probeDuration(reelSource));
            } else {
                log.warn("高光集锦成片失败，work={} 保持占位输出", work.getId());
            }
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

    /** 只重建封面（改了标题/换了底图）：重新定位源与区间后走同一套封面优先级 */
    @Override
    public void buildCover(Work work) {
        if (work == null || work.getId() == null) return;
        String dir = settings.getStr("media_dir", "./media");
        File source;
        double start = 0;
        double dur = 6;
        if (work.getReelSegments() != null && !work.getReelSegments().isBlank()) {
            source = existing(work.getSourcePath());
            List<int[]> segs = parseSegments(work.getReelSegments());
            if (!segs.isEmpty()) {
                start = segs.get(0)[0];
                dur = segs.get(0)[1] - segs.get(0)[0];
            }
        } else {
            source = resolveSource(work);
            Clip clip = work.getClipId() == null ? null : clipMapper.selectById(work.getClipId());
            if (clip != null && clip.getStartSec() != null && clip.getEndSec() != null
                    && clip.getEndSec() > clip.getStartSec()) {
                start = clip.getStartSec();
                dur = clip.getEndSec() - clip.getStartSec();
            }
        }
        File workFile = existing(work.getOutputPath());
        double srcLen = source == null ? -1 : FfmpegSupport.probeDuration(source);
        makeCover(workFile, source, work, dir, start, dur, srcLen);
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

    /**
     * 封面优先级：本作品手工指定底图 &gt; 房间图片素材（按作品 id 轮播）&gt; 源视频截帧。
     * 三种来源都统一由 {@link CoverComposer} 叠标题文字；全失败才退回成片裸抽帧。
     */
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
        String title = coverTitle(work);
        File bg = coverBackground(work);
        if (bg != null) {
            if (coverComposer.fromImage(bg, title, cover)) {
                log.info("封面已用图片素材合成 work={} bg={}", work.getId(), bg.getName());
                return;
            }
            log.warn("图片素材封面失败，退回截帧 work={} bg={}", work.getId(), bg);
        }
        double mid = dur / 2;
        if (source != null
                && coverComposer.fromFrame(source, clampMid(start, mid, srcLen), title, cover)) {
            return;
        }
        if (workFile == null || !ffmpegCover(workFile, cover, mid)) {
            log.warn("封面生成全部失败，work={}", work.getId());
        }
    }

    /** 源文件内的取帧点：段中点，按真实文件长度钳制 */
    private static double clampMid(double start, double mid, double srcLen) {
        double abs = start + mid;
        return srcLen > 0 ? Math.min(Math.max(abs, 0), Math.max(0, srcLen - 0.5)) : Math.max(0, abs);
    }

    private static String coverTitle(Work work) {
        if (work.getTitle() != null && !work.getTitle().isBlank()) return work.getTitle();
        return work.getRoomName() == null || work.getRoomName().isBlank() ? "精彩片段" : work.getRoomName();
    }

    /**
     * 封面底图：作品手工指定优先，否则取该房间的图片素材，按作品 id 轮播
     * （同一主播连发多条时封面不重样）。素材文件被移走则顺延下一张。
     */
    private File coverBackground(Work work) {
        File manual = existing(work.getCoverImage());
        if (manual != null) return manual;
        if (work.getRoomId() == null || work.getId() == null) return null;
        List<CoverAsset> assets = coverAssetMapper.selectList(Wrappers.<CoverAsset>lambdaQuery()
                .eq(CoverAsset::getRoomId, work.getRoomId()).orderByAsc(CoverAsset::getId));
        if (assets.isEmpty()) return null;
        int n = assets.size();
        for (int i = 0; i < n; i++) {
            File f = existing(assets.get((int) Math.floorMod(work.getId() + i, n)).getFilePath());
            if (f != null) return f;
        }
        return null;
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
     * 一次拼接成一支成片，并叠加标题条。单次编码，避免逐段产物再合并。
     * 封面由调用方在成功后另行生成（要从未烧字的源片取帧）。
     * segments 格式 "start,end;start,end"。任何失败返回 false（调用方保持占位输出）。
     */
    private boolean renderReel(File source, String segments, Path out, String title) {
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
        if (renderReelSeekCut(source, segs, srcLen, out, title)) return true;
        return renderReelFilterComplex(source, segs, srcLen, out, title);
    }

    /**
     * 快速路径：逐段把 `-ss` 放在 `-i` 前面（输入端定位，只解码这一段），加标题条后编成参数完全一致的
     * 临时片，再用 concat demuxer 无损拼接。
     *
     * <p>老路径的 trim 滤镜是在解码之后才裁切，所以哪怕只取一小时录制里的十分钟高光，ffmpeg 也要从
     * 第 0 秒解到最后一段的结尾。任一段失败、拼接产物异常或时长对不上，都返回 false 回退老路径。
     */
    private boolean renderReelSeekCut(File source, List<int[]> segs, double srcLen, Path out, String title) {
        String base = out.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path tmpDir = out.getParent().resolve("reel_tmp");
        List<Path> temps = new ArrayList<>();
        Path listFile = tmpDir.resolve(base + "_list.txt");
        double expected = 0;
        try {
            Files.createDirectories(tmpDir);
            String draw = drawTextFilter(title);
            int idx = 0;
            for (int[] seg : segs) {
                double s = Math.max(0, seg[0]);
                double e = seg[1];
                if (srcLen > 0) e = Math.min(e, srcLen);
                double d = e - s;
                if (d < 1) continue;
                Path segFile = tmpDir.resolve(base + "_seg" + idx + ".mp4");
                List<String> cmd = new ArrayList<>(List.of(
                        "ffmpeg", "-hide_banner", "-loglevel", "error",
                        "-ss", fmt(s), "-i", source.getAbsolutePath(), "-t", fmt(d)));
                if (!draw.isEmpty()) cmd.addAll(List.of("-vf", draw));
                cmd.addAll(List.of(
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-ar", "44100", "-ac", "2",
                        "-y", segFile.toString()));
                StringBuilder err = new StringBuilder();
                if (FfmpegSupport.run(cmd, Math.max(120, (long) (d * 4)), err) != 0 || !outIsGood(segFile)) {
                    log.warn("集锦快速切段失败 seg[{}-{}]：{}", s, e, err);
                    return false;
                }
                double got = FfmpegSupport.probeDuration(segFile.toFile());
                if (got <= 0.5 || Math.abs(got - d) > Math.max(2, d * 0.2)) {
                    log.warn("集锦快速切段时长异常 seg[{}-{}] 实得={}s", s, e, String.format("%.1f", got));
                    return false;
                }
                temps.add(segFile);
                expected += d;
                idx++;
            }
            if (temps.isEmpty()) return false;

            StringBuilder list = new StringBuilder();
            for (Path p : temps) {
                list.append("file '").append(p.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
            }
            Files.writeString(listFile, list.toString());
            List<String> cmd = List.of(
                    "ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                    "-c", "copy", "-movflags", "+faststart",
                    "-y", out.toString());
            StringBuilder err = new StringBuilder();
            if (FfmpegSupport.run(cmd, Math.max(180, (long) (expected * 2)), err) != 0 || !outIsGood(out)) {
                log.warn("集锦无损拼接失败: {}", err);
                return false;
            }
            double real = FfmpegSupport.probeDuration(out.toFile());
            if (real <= 0.5 || Math.abs(real - expected) > Math.max(3, expected * 0.1)) {
                log.warn("集锦无损拼接时长异常 期望≈{}s 实得={}s，回退整段裁切", (long) expected,
                        String.format("%.1f", real));
                return false;
            }
            log.info("高光集锦已产出(快速切段): {} 段={} 时长≈{}s", out, temps.size(), (long) real);
            return true;
        } catch (Exception e) {
            log.warn("高光集锦快速切段异常，回退整段裁切: {}", e.toString());
            return false;
        } finally {
            for (Path p : temps) deleteQuietly(p);
            deleteQuietly(listFile);
        }
    }

    /** 标题条滤镜（不含前置输入标签）：title 为空则返回空串，表示不加字 */
    private static String drawTextFilter(String title) {
        if (title == null || title.isBlank()) return "";
        return "drawtext=fontfile=" + FONT_FILE
                + ":text='" + FfmpegSupport.escapeFilterValue(title) + "'"
                + ":fontsize=42:fontcolor=white:borderw=3:bordercolor=black"
                + ":box=1:boxcolor=black@0.45:boxborderw=18"
                + ":x=(w-text_w)/2:y=h*0.08";
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    /** 老路径：filter_complex trim+concat 单次拼接（要解码到最后一段的结尾，长录制明显更慢） */
    private boolean renderReelFilterComplex(File source, List<int[]> segs, double srcLen, Path out, String title) {
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
