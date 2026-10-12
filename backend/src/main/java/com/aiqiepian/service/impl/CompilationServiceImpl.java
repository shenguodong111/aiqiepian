package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.engine.real.FfmpegSupport;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.Compilation;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.CompilationMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.CompilationService;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 合集（M4-2）：ffmpeg concat demuxer 串联多个成片。
 * 优先 -c copy（同封装快速拼接），失败降级 libx264 veryfast + aac 重编码。
 * 渲染为后台短任务：接口立即返回，独立线程执行；CompilationScheduler（5s）只做
 * 假进度推进与重启孤儿恢复，不涉及 PipelineOrchestrator。
 */
@Service
@RequiredArgsConstructor
public class CompilationServiceImpl implements CompilationService {

    private static final Logger log = LoggerFactory.getLogger(CompilationServiceImpl.class);
    private static final Set<String> MEMBER_STATUS = Set.of("READY", "APPROVED", "PUBLISHED");
    private static final long COPY_TIMEOUT_SEC = 600;
    private static final long REENCODE_TIMEOUT_SEC = 1800;

    private final CompilationMapper compilationMapper;
    private final WorkMapper workMapper;
    private final ClipMapper clipMapper;
    private final SettingsService settings;

    /** 单线程渲染队列：合集是低频短任务，无需并发 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "compilation-render");
        t.setDaemon(true);
        return t;
    });
    /** 正在/已投递渲染的合集 id（Set.add 幂等锁，防 render 与调度器重投） */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    @Override
    public PageResult<Compilation> list(Integer page, Integer size) {
        List<Compilation> list = compilationMapper.selectList(
                Wrappers.<Compilation>lambdaQuery().orderByDesc(Compilation::getId));
        return PageResult.of(list, page, size);
    }

    @Override
    public Compilation create(Map<String, Object> body) {
        String title = BodyUtils.str(body, "title", null);
        if (title == null || title.isBlank()) throw new BizException("合集标题必填");
        title = title.trim();
        if (title.length() > 200) throw new BizException("合集标题过长（≤200）");
        List<Long> ids = parseWorkIds(body);
        if (ids.isEmpty()) throw new BizException("请至少选择一个作品");
        if (ids.size() > 100) throw new BizException("单个合集最多 100 个作品");

        int total = 0;
        for (Long id : ids) {
            Work w = id == null ? null : workMapper.selectById(id);
            if (w == null) throw new BizException("作品 " + id + " 不存在");
            if (!MEMBER_STATUS.contains(w.getStatus())) {
                throw new BizException("作品《" + w.getTitle() + "》状态为 " + w.getStatus() + "，仅 READY/APPROVED/PUBLISHED 可入合集");
            }
            if (w.getOutputPath() == null || w.getOutputPath().isBlank()) {
                throw new BizException("作品《" + w.getTitle() + "》缺少成片文件");
            }
            total += snapshotDurationSec(w);
        }

        Compilation c = new Compilation();
        c.setTitle(title);
        String joined = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        if (joined.length() > 1000) throw new BizException("作品过多（work_ids 超出 1000 字符）");
        c.setWorkIds(joined);
        c.setWorkCount(ids.size());
        c.setTotalDurationSec(total);
        c.setStatus("DRAFT");
        c.setOutputPath(null);
        c.setProgress(0);
        c.setErrorMsg(null);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        compilationMapper.insert(c);
        return c;
    }

    @Override
    public Compilation render(long id) {
        Compilation c = compilationMapper.selectById(id);
        if (c == null) throw new BizException(404, "合集不存在");
        if ("RENDERING".equals(c.getStatus()) && running.contains(id)) return c; // 已在渲染，幂等返回
        c.setStatus("RENDERING");
        c.setProgress(0);
        c.setErrorMsg(null);
        c.setUpdatedAt(LocalDateTime.now());
        compilationMapper.updateById(c);
        enqueue(id);
        return compilationMapper.selectById(id);
    }

    @Override
    public void delete(long id) {
        Compilation c = compilationMapper.selectById(id);
        if (c == null) throw new BizException(404, "合集不存在");
        if ("RENDERING".equals(c.getStatus()) && running.contains(id)) throw new BizException("生成中的合集不可删除");
        String out = c.getOutputPath();
        if (out != null && !out.isBlank()) {
            try {
                Files.deleteIfExists(Path.of(out));
            } catch (Exception e) {
                log.warn("删除合集产物失败 {}: {}", out, e.toString());
            }
        }
        compilationMapper.deleteById(id);
    }

    @Override
    public void schedulerTick() {
        for (Compilation c : compilationMapper.selectList(
                Wrappers.<Compilation>lambdaQuery().eq(Compilation::getStatus, "RENDERING"))) {
            if (running.contains(c.getId())) {
                // 渲染线程在后台同步执行：调度器只推进假进度（终态由渲染线程写 100）
                int p = Math.min(95, nz(c.getProgress()) + 10);
                if (p != nz(c.getProgress())) {
                    c.setProgress(p);
                    c.setUpdatedAt(LocalDateTime.now());
                    compilationMapper.updateById(c);
                }
            } else {
                // 重启遗留的 RENDERING 孤儿：重新投递（concat 幂等，直接覆盖产物）
                log.info("合集 {} 处于 RENDERING 但无渲染线程（可能服务重启），重新投递", c.getId());
                enqueue(c.getId());
            }
        }
    }

    // ---------- 内部 ----------

    private void enqueue(long id) {
        if (!running.add(id)) return;
        executor.submit(() -> {
            try {
                doRender(id);
            } catch (Exception e) {
                log.warn("合集渲染异常 id={}: {}", id, e.toString());
                markFailed(id, "渲染异常: " + e.getMessage());
            } finally {
                running.remove(id);
            }
        });
    }

    /** 后台线程：写 concat list → -c copy → 失败降级重编码 → READY/FAILED */
    private void doRender(long id) {
        Compilation c = compilationMapper.selectById(id);
        if (c == null || !"RENDERING".equals(c.getStatus())) return;
        String dir = settings.getStr("media_dir", "./media");
        try {
            List<String> files = new ArrayList<>();
            for (Long wid : splitIds(c.getWorkIds())) {
                Work w = wid == null ? null : workMapper.selectById(wid);
                if (w == null || w.getOutputPath() == null || w.getOutputPath().isBlank()) {
                    markFailed(id, "成员作品 " + wid + " 或其成片文件已丢失");
                    return;
                }
                Path p = Path.of(w.getOutputPath()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(p)) {
                    markFailed(id, "成员作品文件不存在: " + p);
                    return;
                }
                files.add(p.toString().replace('\\', '/'));
            }

            Path compDir = Path.of(dir, "compilations").toAbsolutePath().normalize();
            Files.createDirectories(compDir);
            Path out = compDir.resolve("compilation_" + id + ".mp4");
            Path list = compDir.resolve("compilation_" + id + "_list.txt");
            StringBuilder sb = new StringBuilder();
            for (String f : files) {
                // concat demuxer：单引号包裹，内部单引号按 '\'' 转义；Windows 反斜杠统一为 / 避免转义歧义
                sb.append("file '").append(f.replace("'", "'\\''")).append("'\n");
            }
            Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);

            StringBuilder tail = new StringBuilder();
            int rc = FfmpegSupport.run(List.of("ffmpeg", "-y", "-f", "concat", "-safe", "0",
                    "-i", list.toString().replace('\\', '/'), "-c", "copy", out.toString()), COPY_TIMEOUT_SEC, tail);
            if (rc != 0) {
                log.info("合集 {} concat -c copy 失败(rc={})，降级重编码: {}", id, rc, tail);
                tail.setLength(0);
                rc = FfmpegSupport.run(List.of("ffmpeg", "-y", "-f", "concat", "-safe", "0",
                        "-i", list.toString().replace('\\', '/'),
                        "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", out.toString()),
                        REENCODE_TIMEOUT_SEC, tail);
            }
            if (rc != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
                markFailed(id, "ffmpeg 拼接失败 rc=" + rc + "：" + tailTail(tail));
                return;
            }
            c = compilationMapper.selectById(id);
            if (c == null || !"RENDERING".equals(c.getStatus())) return; // 已被删除/重置
            c.setStatus("READY");
            c.setProgress(100);
            c.setErrorMsg(null);
            c.setOutputPath(dir + "/compilations/compilation_" + id + ".mp4");
            c.setUpdatedAt(LocalDateTime.now());
            compilationMapper.updateById(c);
            log.info("合集 {} 渲染完成 → {}", id, c.getOutputPath());
        } catch (Exception e) {
            markFailed(id, "渲染异常: " + e);
        } finally {
            try {
                Path list = Path.of(dir, "compilations", "compilation_" + id + "_list.txt");
                Files.deleteIfExists(list);
            } catch (Exception ignore) {
            }
        }
    }

    private void markFailed(long id, String msg) {
        Compilation c = compilationMapper.selectById(id);
        if (c == null) return;
        c.setStatus("FAILED");
        c.setProgress(0);
        c.setErrorMsg(msg == null ? "渲染失败" : msg.substring(0, Math.min(480, msg.length())));
        c.setUpdatedAt(LocalDateTime.now());
        compilationMapper.updateById(c);
        log.warn("合集 {} FAILED: {}", id, msg);
    }

    /** 时长快照：ffprobe 真实文件优先，文件不存在时回退片段时长/0 */
    private int snapshotDurationSec(Work w) {
        File f = Path.of(w.getOutputPath()).toFile();
        double d = FfmpegSupport.probeDuration(f);
        if (d > 0) return (int) Math.round(d);
        Clip clip = w.getClipId() == null ? null : clipMapper.selectById(w.getClipId());
        return clip != null && clip.getDurationSec() != null ? clip.getDurationSec() : 0;
    }

    private static List<Long> parseWorkIds(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("work_ids");
        if (raw == null) raw = body == null ? null : body.get("workIds");
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) ids.add(n.longValue());
                else if (o != null && !String.valueOf(o).isBlank()) ids.add(Long.parseLong(String.valueOf(o).trim()));
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
            }
        }
        return ids;
    }

    private static List<Long> splitIds(String joined) {
        List<Long> ids = new ArrayList<>();
        if (joined == null) return ids;
        for (String part : joined.split(",")) {
            if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
        }
        return ids;
    }

    private static String tailTail(StringBuilder sb) {
        String s = sb == null ? "" : sb.toString().trim();
        return s.length() > 200 ? s.substring(s.length() - 200) : s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
