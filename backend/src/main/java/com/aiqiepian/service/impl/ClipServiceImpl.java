package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.PipelineJobMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.ClipService;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.WorkService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClipServiceImpl implements ClipService {

    private static final Logger log = LoggerFactory.getLogger(ClipServiceImpl.class);

    private final ClipMapper clipMapper;
    private final WorkMapper workMapper;
    private final PipelineJobMapper jobMapper;
    private final WorkService workService;
    private final PipelineService pipeline;

    private boolean analyzing(Long sessionId) {
        if (sessionId == null) return false;
        return jobMapper.selectCount(Wrappers.<PipelineJob>lambdaQuery()
                .eq(PipelineJob::getJobType, "ANALYZE")
                .eq(PipelineJob::getEntityType, "session")
                .eq(PipelineJob::getEntityId, sessionId)
                .in(PipelineJob::getStatus, "PENDING", "RUNNING")) > 0;
    }

    /**
     * 分析还在排队/执行的那场录像，不允许基于它的片段建作品：分析收尾会先回收上一轮的候选片段
     * （{@code dropStaleClips}）再写入新结果，正挑着的片段下一秒就可能被换掉，拼出来的成片与
     * 挑的时间段对不上。
     */
    private void refuseIfAnalyzing(Long sessionId) {
        if (analyzing(sessionId)) {
            throw new BizException("会话 " + sessionId + " 还在分析中，等这一场分析结束再挑选片段"
                    + "（分析会替换掉本轮候选片段，现在拼的作品会对不上挑的时间段）");
        }
    }

    @Override
    public PageResult<Clip> list(Long sessionId, Long roomId, Integer minScore, String status, Integer page, Integer size) {
        // 排序按「最新一场录像 → 该场内的时间顺序」：过去单纯按 id 倒序，同一场的片段之间会夹进
        // 别的录像的片段，想挑几段拼成片时得来回翻页对时间。
        var q = Wrappers.<Clip>lambdaQuery()
                .orderByDesc(Clip::getSessionId)
                .orderByAsc(Clip::getStartSec)
                .orderByAsc(Clip::getId);
        if (sessionId != null) q.eq(Clip::getSessionId, sessionId);
        if (roomId != null) q.eq(Clip::getRoomId, roomId);
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
        refuseIfAnalyzing(c.getSessionId());
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
                // 批量语义是"能做的做，做不了的跳过"，这里跳过而不是整批报错
                if (analyzing(c.getSessionId())) continue;
                c.setStatus("RENDERED");
                clipMapper.updateById(c);
                made.add(pipeline.makeWork(c));
            }
        }
        return made;
    }

    @Override
    public Work stitch(List<Long> ids) {
        List<Clip> picked = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                Clip c = id == null ? null : clipMapper.selectById(id);
                if (c != null) picked.add(c);
            }
        }
        Map<Long, Clip> uniq = new LinkedHashMap<>();
        for (Clip c : picked) uniq.put(c.getId(), c);
        picked = new ArrayList<>(uniq.values());
        if (picked.size() < 2) throw new BizException("请至少勾选两个片段再拼接（单个片段用「生成作品」即可）");

        Long session = picked.get(0).getSessionId();
        refuseIfAnalyzing(session);
        for (Clip c : picked) {
            if (c.getSessionId() == null || !c.getSessionId().equals(session)) {
                throw new BizException("只能拼接同一场录像里的片段（#%d 属于会话 %s，与 %s 不同）"
                        .formatted(c.getId(), c.getSessionId(), session));
            }
            if (c.getStartSec() == null || c.getEndSec() == null || c.getEndSec() <= c.getStartSec()) {
                throw new BizException("片段 #" + c.getId() + " 的时间区间无效，请先编辑修正");
            }
        }
        // 重叠的两段拼起来会是"同一段画面播两遍"，直接拒绝并指出是哪两条
        List<Clip> byStart = new ArrayList<>(picked);
        byStart.sort(java.util.Comparator.comparingInt(Clip::getStartSec));
        for (int i = 1; i < byStart.size(); i++) {
            Clip a = byStart.get(i - 1), b = byStart.get(i);
            if (b.getStartSec() < a.getEndSec()) {
                throw new BizException("片段 #%d（%ds–%ds）与 #%d（%ds–%ds）时间重叠，请调整后再拼"
                        .formatted(a.getId(), a.getStartSec(), a.getEndSec(),
                                b.getId(), b.getStartSec(), b.getEndSec()));
            }
        }
        for (Clip c : picked) {
            c.setStatus("RENDERED");
            clipMapper.updateById(c);
        }
        return pipeline.makeStitchWork(picked);
    }

    @Override
    public Map<String, Object> delete(long id, boolean force, boolean deleteFiles) {
        Clip c = clipMapper.selectById(id);
        if (c == null) throw new BizException(404, "不存在");
        Stats s = new Stats();
        deleteOne(c, force, deleteFiles, s);
        return stats(s, List.of(id), force, deleteFiles);
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
        return stats(s, ids, force, deleteFiles);
    }

    private static Map<String, Object> stats(Stats s, List<Long> ids, boolean force, boolean deleteFiles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed_clips", s.clips);
        m.put("removed_works", s.works);
        m.put("skipped_published", s.skipped);
        m.put("freed_bytes", s.freed);
        // 审计：片段删除会连带它的作品（含审核/发布记录），出问题时靠这行日志归因
        log.info("审计删除[片段] 勾选={} 项 force={} 连带删除文件={} -> 删除片段={} 连带作品={} 跳过已发布={} 回收={} KB",
                ids == null ? 0 : ids.size(), force, deleteFiles, s.clips, s.works, s.skipped, s.freed / 1024);
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
