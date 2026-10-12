package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.ClipService;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.WorkService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClipServiceImpl implements ClipService {

    private final ClipMapper clipMapper;
    private final WorkMapper workMapper;
    private final WorkService workService;
    private final PipelineService pipeline;

    @Override
    public PageResult<Clip> list(Long sessionId, Integer minScore, String status, Integer page, Integer size) {
        var q = Wrappers.<Clip>lambdaQuery().orderByDesc(Clip::getId);
        if (sessionId != null) q.eq(Clip::getSessionId, sessionId);
        if (minScore != null) q.ge(Clip::getScore, minScore);
        if (status != null && !status.isEmpty()) q.eq(Clip::getStatus, status);
        return PageResult.of(clipMapper.selectList(q), page, size);
    }

    @Override
    public Clip updateRange(long id, Integer startSec, Integer endSec) {
        Clip c = clipMapper.selectById(id);
        if (c == null) throw new BizException(404, "不存在");
        if (startSec != null) c.setStartSec(startSec);
        if (endSec != null) c.setEndSec(endSec);
        c.setDurationSec(c.getEndSec() - c.getStartSec());
        clipMapper.updateById(c);
        return c;
    }

    @Override
    public Clip discard(long id) {
        Clip c = clipMapper.selectById(id);
        if (c == null) throw new BizException(404, "不存在");
        c.setStatus("DISCARDED");
        clipMapper.updateById(c);
        return c;
    }

    @Override
    public Clip restore(long id) {
        Clip c = clipMapper.selectById(id);
        if (c == null) throw new BizException(404, "不存在");
        if (!"DISCARDED".equals(c.getStatus())) throw new BizException("仅已丢弃的片段可恢复");
        c.setStatus("PENDING");
        clipMapper.updateById(c);
        return c;
    }

    @Override
    public Work makeWork(long id) {
        Clip c = clipMapper.selectById(id);
        if (c == null) throw new BizException(404, "不存在");
        c.setStatus("RENDERED");
        clipMapper.updateById(c);
        return pipeline.makeWork(c);
    }

    @Override
    public List<Work> batchMakeWork(List<Long> ids) {
        List<Work> made = new ArrayList<>();
        if (ids == null) return made;
        for (Long id : ids) {
            Clip c = id == null ? null : clipMapper.selectById(id);
            if (c != null && "PENDING".equals(c.getStatus())) {
                c.setStatus("RENDERED");
                clipMapper.updateById(c);
                made.add(pipeline.makeWork(c));
            }
        }
        return made;
    }

    @Override
    public Map<String, Object> delete(long id, boolean force, boolean deleteFiles) {
        Clip c = clipMapper.selectById(id);
        if (c == null) throw new BizException(404, "不存在");
        Stats s = new Stats();
        deleteOne(c, force, deleteFiles, s);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed_clips", s.clips);
        m.put("removed_works", s.works);
        m.put("skipped_published", s.skipped);
        m.put("freed_bytes", s.freed);
        return m;
    }

    @Override
    public Map<String, Object> batchDelete(List<Long> ids, boolean force, boolean deleteFiles) {
        Stats s = new Stats();
        if (ids != null) {
            for (Long id : ids) {
                Clip c = id == null ? null : clipMapper.selectById(id);
                if (c != null) deleteOne(c, force, deleteFiles, s);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed_clips", s.clips);
        m.put("removed_works", s.works);
        m.put("skipped_published", s.skipped);
        m.put("freed_bytes", s.freed);
        return m;
    }

    /** 级联删片段派生作品（复用 WorkService 语义），再删片段自身文件与记录。 */
    private void deleteOne(Clip c, boolean force, boolean deleteFiles, Stats s) {
        List<Long> workIds = new ArrayList<>();
        for (Work w : workMapper.selectList(Wrappers.<Work>lambdaQuery().eq(Work::getClipId, c.getId()))) {
            workIds.add(w.getId());
        }
        boolean blockClip = false;
        if (!workIds.isEmpty()) {
            Map<String, Object> wr = workService.batchDelete(workIds, force, deleteFiles);
            s.works += ((Number) wr.getOrDefault("removed_works", 0)).intValue();
            s.freed += ((Number) wr.getOrDefault("freed_bytes", 0L)).longValue();
            int skipped = ((Number) wr.getOrDefault("skipped_published", 0)).intValue();
            if (skipped > 0) {
                s.skipped += skipped;
                blockClip = !force; // 有已发布作品未 force：保留片段，避免远端稿件失去本地来源引用
            }
        }
        if (!blockClip) {
            if (deleteFiles) s.freed += deleteIfPresent(c.getFilePath());
            clipMapper.deleteById(c.getId());
            s.clips++;
        }
    }

    private static final class Stats {
        int clips, works, skipped;
        long freed;
    }

    private static long deleteIfPresent(String path) {
        if (path == null || path.isBlank()) return 0;
        File f = new File(path);
        if (!f.isFile()) return 0;
        long len = f.length();
        return f.delete() ? len : 0;
    }
}
