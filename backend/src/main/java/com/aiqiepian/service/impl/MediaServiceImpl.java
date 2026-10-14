package com.aiqiepian.service.impl;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.common.SessionFiles;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.CoverAsset;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.ReviewRecord;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.CoverAssetMapper;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.mapper.ReviewRecordMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.ClipService;
import com.aiqiepian.service.MediaService;
import com.aiqiepian.service.SettingsService;
import com.aiqiepian.service.WorkService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private final RecordingSessionMapper sessionMapper;
    private final WorkMapper workMapper;
    private final ClipMapper clipMapper;
    private final CoverAssetMapper coverAssetMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final PublishTaskMapper publishTaskMapper;
    private final SettingsService settings;
    private final WorkService workService;
    private final ClipService clipService;

    private static final class Ref {
        final String type;
        final long id;
        Ref(String type, long id) { this.type = type; this.id = id; }
    }

    @Override
    public PageResult<Map<String, Object>> list(String category, Integer page, Integer size) {
        String dir = settings.getStr("media_dir", "./media");
        List<Map<String, Object>> list = new ArrayList<>();
        String cat = category == null || category.isEmpty() ? "recordings" : category;
        switch (cat) {
            case "recordings" -> {
                for (RecordingSession s : sessionMapper.selectList(
                        Wrappers.<RecordingSession>lambdaQuery().orderByDesc(RecordingSession::getId))) {
                    File f = existing(s.getFilePath());
                    if (f != null) list.add(entry(f, s.getStartedAt(), new Ref("session", s.getId())));
                }
            }
            case "clips" -> {
                Map<Long, Work> byClip = worksByClipId();
                for (Clip c : clipMapper.selectList(
                        Wrappers.<Clip>lambdaQuery().orderByDesc(Clip::getId))) {
                    Work w = c.getId() == null ? null : byClip.get(c.getId());
                    File own = w == null ? null : existing(w.getOutputPath());
                    File f = own != null ? own : clipPlayable(c, byClip);
                    if (f == null) continue;
                    // 片段本身不在磁盘上落独立文件（一条 60s 原画就是一两百 MB，整库要翻出几十 GB），
                    // 播的是它的成片；没有成片时回落到所属会话的原始录制。过去这里把那个原始录制的
                    // 文件名和大小原样抄成一行，于是同一场录制出现 N 行"一模一样的 953 MB 片段"，
                    // 看着就是重复文件。现在按片段自身命名，大小也不冒领整场录制。
                    list.add(clipEntry(c, f, own));
                }
            }
            case "subtitles" -> {
                for (Work w : workMapper.selectList(Wrappers.<Work>lambdaQuery().orderByAsc(Work::getId))) {
                    if (!Boolean.TRUE.equals(w.getSubtitleEnabled())) continue;
                    File f = existing(dir + "/subtitles/work_" + w.getId() + ".srt");
                    if (f != null) list.add(entry(f, w.getCreatedAt(), new Ref("work", w.getId())));
                }
            }
            case "covers" -> {
                for (Work w : workMapper.selectList(Wrappers.<Work>lambdaQuery().orderByAsc(Work::getId))) {
                    File f = existing(w.getCoverPath());
                    if (f != null) list.add(entry(f, w.getCreatedAt(), new Ref("work", w.getId())));
                }
            }
            case "works" -> {
                for (Work w : workMapper.selectList(Wrappers.<Work>lambdaQuery().orderByDesc(Work::getId))) {
                    File f = existing(w.getOutputPath());
                    if (f != null) list.add(entry(f, w.getUpdatedAt(), new Ref("work", w.getId())));
                }
            }
            default -> { }
        }
        return PageResult.of(list, page, size);
    }

    @Override
    public Map<String, Object> deleteSelected(String category, List<Long> ids, boolean force) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            out.put("removed", 0); out.put("freed_bytes", 0L); out.put("skipped_published", 0);
            return out;
        }
        String cat = category == null ? "" : category;
        switch (cat) {
            case "clips" -> {
                Map<String, Object> r = clipService.batchDelete(ids, force, true);
                out.put("removed", num(r.get("removed_clips")));
                out.put("removed_works", num(r.get("removed_works")));
                out.put("freed_bytes", num(r.get("freed_bytes")));
                out.put("skipped_published", num(r.get("skipped_published")));
            }
            case "works" -> {
                Map<String, Object> r = workService.batchDelete(ids, force, true);
                out.put("removed", num(r.get("removed_works")));
                out.put("freed_bytes", num(r.get("freed_bytes")));
                out.put("skipped_published", num(r.get("skipped_published")));
            }
            case "recordings" -> {
                int removed = 0; long freed = 0; int skippedPub = 0;
                for (Long sid : ids) {
                    RecordingSession s = sessionMapper.selectById(sid);
                    if (s == null) continue;
                    // 级联：先删该会话下的片段（连带其作品/审核/发布记录，已发布默认受保护）
                    List<Long> clipIds = new ArrayList<>();
                    for (Clip c : clipMapper.selectList(Wrappers.<Clip>lambdaQuery().eq(Clip::getSessionId, sid))) {
                        if (c.getId() != null) clipIds.add(c.getId());
                    }
                    if (!clipIds.isEmpty()) {
                        Map<String, Object> cr = clipService.batchDelete(clipIds, force, true);
                        freed += num(cr.get("freed_bytes"));
                        skippedPub += (int) num(cr.get("skipped_published"));
                    }
                    File f = existing(s.getFilePath());
                    if (f != null) { freed += f.length(); if (!f.delete()) f.deleteOnExit(); }
                    long remaining = clipMapper.selectCount(Wrappers.<Clip>lambdaQuery().eq(Clip::getSessionId, sid));
                    if (remaining == 0) {
                        sessionMapper.deleteById(sid);   // 会话及其全部下游记录彻底消失
                    } else {
                        // 仍有已发布作品依赖：保留会话记录，仅解除原始文件引用
                        sessionMapper.update(null, Wrappers.<RecordingSession>lambdaUpdate()
                                .set(RecordingSession::getFilePath, null)
                                .set(RecordingSession::getFileSizeBytes, 0L)
                                .eq(RecordingSession::getId, sid));
                    }
                    removed++;
                }
                out.put("removed", removed); out.put("freed_bytes", freed); out.put("skipped_published", skippedPub);
            }
            case "subtitles" -> {
                String dir = settings.getStr("media_dir", "./media");
                int removed = 0; long freed = 0;
                for (Long wid : ids) {
                    File f = existing(dir + "/subtitles/work_" + wid + ".srt");
                    if (f != null) { freed += f.length(); if (f.delete()) removed++; }
                }
                out.put("removed", removed); out.put("freed_bytes", freed); out.put("skipped_published", 0);
            }
            case "covers" -> {
                int removed = 0; long freed = 0;
                for (Long wid : ids) {
                    Work w = workMapper.selectById(wid);
                    if (w == null) continue;
                    File f = existing(w.getCoverPath());
                    if (f != null) {
                        freed += f.length();
                        if (f.delete()) {
                            removed++;
                            workMapper.update(null, Wrappers.<Work>lambdaUpdate()
                                    .set(Work::getCoverPath, null).eq(Work::getId, wid));
                        }
                    }
                }
                out.put("removed", removed); out.put("freed_bytes", freed); out.put("skipped_published", 0);
            }
            default -> {
                out.put("removed", 0); out.put("freed_bytes", 0L); out.put("skipped_published", 0);
            }
        }
        return out;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    @Override
    public Map<String, Object> stats() {
        long rec = 0, works = 0, covers = 0, subs = 0;
        for (RecordingSession s : sessionMapper.selectList(null)) {
            File f = existing(s.getFilePath());
            if (f != null) rec += f.length();
        }
        String dir = settings.getStr("media_dir", "./media");
        for (Work w : workMapper.selectList(null)) {
            File o = existing(w.getOutputPath());
            if (o != null) works += o.length();
            File c = existing(w.getCoverPath());
            if (c != null) covers += c.length();
            File srt = existing(dir + "/subtitles/work_" + w.getId() + ".srt");
            if (srt != null) subs += srt.length();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordings_bytes", rec);
        m.put("works_bytes", works);
        m.put("subtitles_bytes", subs);
        m.put("covers_bytes", covers);
        m.put("total_bytes", rec + works + covers + subs);
        return m;
    }

    @Override
    public File resolveStream(String type, long id) {
        if (type == null) return null;
        switch (type) {
            case "session" -> {
                RecordingSession s = sessionMapper.selectById(id);
                // 录制中的会话最终文件还没生成（只有 .segNNN.mp4），回退到最新分段才能边录边看
                return s == null ? null : SessionFiles.playable(s.getFilePath());
            }
            case "work" -> {
                Work w = workMapper.selectById(id);
                return w == null ? null : existing(w.getOutputPath());
            }
            case "cover" -> {
                Work w = workMapper.selectById(id);
                return w == null ? null : existing(w.getCoverPath());
            }
            case "asset" -> {
                CoverAsset a = coverAssetMapper.selectById(id);
                return a == null ? null : existing(a.getFilePath());
            }
            case "clip" -> {
                Clip c = clipMapper.selectById(id);
                if (c == null) return null;
                return clipPlayable(c, worksByClipId());
            }
            default -> {
                return null;
            }
        }
    }

    /** 片段可播文件：优先其成片（work.output），无成片则回落所属会话的原始录制 */
    private File clipPlayable(Clip c, Map<Long, Work> byClip) {
        Work w = c.getId() == null ? null : byClip.get(c.getId());
        if (w != null) {
            File out = existing(w.getOutputPath());
            if (out != null) return out;
        }
        if (c.getSessionId() != null) {
            RecordingSession s = sessionMapper.selectById(c.getSessionId());
            if (s != null) return existing(s.getFilePath());
        }
        return null;
    }

    private Map<Long, Work> worksByClipId() {
        Map<Long, Work> m = new HashMap<>();
        for (Work w : workMapper.selectList(Wrappers.<Work>lambdaQuery().isNotNull(Work::getClipId))) {
            if (w.getClipId() != null) m.putIfAbsent(w.getClipId(), w);
        }
        return m;
    }

    /**
     * 需求文档 P8-03：清理"已发布成片的原始录制"。此处移除记录并删除磁盘原始录制文件。
     */
    @Override
    public Map<String, Object> cleanup(String scope) {
        int removed = 0;
        long freed = 0;
        if (scope == null || "published_recordings".equals(scope)) {
            Set<Long> sessionIds = new HashSet<>();
            List<Work> published = workMapper.selectList(Wrappers.<Work>lambdaQuery().eq(Work::getStatus, "PUBLISHED"));
            for (Work w : published) {
                Clip c = w.getClipId() == null ? null : clipMapper.selectById(w.getClipId());
                if (c != null && c.getSessionId() != null) sessionIds.add(c.getSessionId());
            }
            for (Long sid : sessionIds) {
                RecordingSession s = sessionMapper.selectById(sid);
                if (s != null && "FINISHED".equals(s.getStatus()) && s.getFilePath() != null) {
                    File f = existing(s.getFilePath());
                    if (f != null) {
                        freed += f.length();
                        if (!f.delete()) f.deleteOnExit();
                    }
                    sessionMapper.update(null, Wrappers.<RecordingSession>lambdaUpdate()
                            .set(RecordingSession::getFilePath, null)
                            .set(RecordingSession::getFileSizeBytes, 0L)
                            .eq(RecordingSession::getId, sid));
                    removed++;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope == null ? "published_recordings" : scope);
        m.put("removed", removed);
        m.put("removed_sessions", removed);
        m.put("freed_bytes", freed);
        return m;
    }

    /**
     * 一键清理冗余：每会话仅保留评分最高的前 keepPerSession 个片段，多余片段连同其作品/审核/发布记录/磁盘文件删除；
     * 可选 purge 扫描 media 目录删除数据库不再引用的孤儿文件。已发布(PUBLISHED)作品与其文件一律保留；房间/会话记录不删。
     */
    @Override
    public Map<String, Object> cleanupRedundant(Integer keepPerSession, boolean deleteFiles, boolean purgeOrphans) {
        int keep = keepPerSession == null || keepPerSession < 1 ? 3 : keepPerSession;
        int removedClips = 0, removedWorks = 0, skippedPub = 0, orphans = 0;
        long freed = 0;

        for (RecordingSession s : sessionMapper.selectList(null)) {
            List<Clip> clips = clipMapper.selectList(Wrappers.<Clip>lambdaQuery()
                    .eq(Clip::getSessionId, s.getId())
                    .orderByDesc(Clip::getScore).orderByAsc(Clip::getId));
            for (int i = keep; i < clips.size(); i++) {
                Clip c = clips.get(i);
                for (Work w : workMapper.selectList(Wrappers.<Work>lambdaQuery().eq(Work::getClipId, c.getId()))) {
                    if ("PUBLISHED".equals(w.getStatus())) { skippedPub++; continue; } // 已发布不动
                    if (deleteFiles) freed += deleteIfPresent(w.getOutputPath()) + deleteIfPresent(w.getCoverPath());
                    reviewRecordMapper.delete(Wrappers.<ReviewRecord>lambdaQuery().eq(ReviewRecord::getWorkId, w.getId()));
                    publishTaskMapper.delete(Wrappers.<PublishTask>lambdaQuery().eq(PublishTask::getWorkId, w.getId()));
                    workMapper.deleteById(w.getId());
                    removedWorks++;
                }
                if (deleteFiles) freed += deleteIfPresent(c.getFilePath());
                clipMapper.deleteById(c.getId());
                removedClips++;
            }
        }

        if (purgeOrphans) {
            Set<String> ref = new HashSet<>();
            for (RecordingSession s : sessionMapper.selectList(null)) addRef(ref, s.getFilePath());
            for (Work w : workMapper.selectList(null)) {
                addRef(ref, w.getOutputPath());
                addRef(ref, w.getCoverPath());
                addRef(ref, w.getCoverImage());
            }
            String dir = settings.getStr("media_dir", "./media");
            for (String sub : new String[]{"recordings", "works", "covers", "clips", "subtitles"}) {
                File[] fs = new File(dir, sub).listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    // covers/assets、covers/manual 是封面素材子目录，由 cover_asset 表自己管生命周期
                    if (!f.isFile()) continue;
                    if (ref.contains(canonical(f.getAbsolutePath()))) continue;
                    long len = f.length();
                    if (!deleteFiles) { orphans++; continue; }
                    if (f.delete()) { orphans++; freed += len; }
                }
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keep_per_session", keep);
        m.put("removed_clips", removedClips);
        m.put("removed_works", removedWorks);
        m.put("removed_orphans", orphans);
        m.put("skipped_published", skippedPub);
        m.put("freed_bytes", freed);
        m.put("delete_files", deleteFiles);
        m.put("purge_orphans", purgeOrphans);
        return m;
    }

    /** 路径存在且为普通文件则返回，否则 null */
    private static File existing(String path) {
        if (path == null || path.isBlank()) return null;
        File f = new File(path);
        return f.isFile() ? f : null;
    }

    private static long deleteIfPresent(String path) {
        File f = existing(path);
        if (f == null) return 0;
        long len = f.length();
        return f.delete() ? len : 0;
    }

    private static void addRef(Set<String> set, String path) {
        if (path != null && !path.isBlank()) set.add(canonical(path));
    }

    /** 规范化路径（消除 Windows 的 `.\`、相对段），用于集合比对，避免误删仍被引用的文件 */
    private static String canonical(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception e) {
            return new File(path).getAbsolutePath();
        }
    }
    /** 文件行结构：name/path/size_bytes/created_at/ref{type,id}（path 为绝对路径，本地可直接打开） */
    private static Map<String, Object> entry(File f, Object createdAt, Ref ref) {
        String p = f.getAbsolutePath().replace("\\.\\", "\\");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", basename(p));
        m.put("path", p);
        m.put("size_bytes", f.length());
        m.put("created_at", createdAt);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", ref.type);
        r.put("id", ref.id);
        m.put("ref", r);
        return m;
    }

    /**
     * 片段行：名字带自己的编号与时间区间（不再冒用整场录制的文件名），
     * 没有自己的成片时大小记 0 并注明是原录制上的一段引用——否则 N 条片段会被看成 N 个重复的大文件。
     */
    private static Map<String, Object> clipEntry(Clip c, File playable, File ownOutput) {
        String p = playable.getAbsolutePath().replace("\\.\\", "\\");
        Map<String, Object> m = new LinkedHashMap<>();
        boolean hasOwn = ownOutput != null;
        m.put("name", "片段#" + c.getId() + " · " + (c.getRoomName() == null ? "" : c.getRoomName() + " ")
                + clock(c.getStartSec()) + "–" + clock(c.getEndSec()) + (hasOwn ? "" : "（引用原录制）"));
        m.put("path", p);
        m.put("size_bytes", hasOwn ? playable.length() : 0L);
        m.put("created_at", c.getCreatedAt());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "clip");
        r.put("id", c.getId());
        m.put("ref", r);
        return m;
    }

    private static String clock(Integer sec) {
        int s = sec == null ? 0 : sec;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static String basename(String p) {
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i < 0 ? p : p.substring(i + 1);
    }
}
