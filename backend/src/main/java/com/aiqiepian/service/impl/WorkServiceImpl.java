package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.ReviewRecord;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.ReviewRecordMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.PublishService;
import com.aiqiepian.service.SettingsService;
import com.aiqiepian.service.TitleGenerator;
import com.aiqiepian.service.WorkService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkServiceImpl implements WorkService {

    private final WorkMapper workMapper;
    private final ClipMapper clipMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final PublishTaskMapper publishTaskMapper;
    private final SettingsService settings;
    private final PipelineService pipeline;
    private final PublishService publishService;
    private final TitleGenerator titleGenerator;

    @Override
    public PageResult<Work> list(String status, Integer page, Integer size) {
        var q = Wrappers.<Work>lambdaQuery().orderByDesc(Work::getId);
        if (status != null && !status.isEmpty()) q.eq(Work::getStatus, status);
        List<Work> list = workMapper.selectList(q);
        return PageResult.of(list, page, size);
    }

    @Override
    public Work createFromClip(Map<String, Object> body) {
        Long clipId = BodyUtils.longVal(body, "clipId");
        if (clipId == null) clipId = BodyUtils.longVal(body, "clip_id");
        Clip c = clipId == null ? null : clipMapper.selectById(clipId);
        if (c == null) throw new BizException("片段不存在");
        c.setStatus("RENDERED");
        clipMapper.updateById(c);
        return pipeline.makeWork(c);
    }

    @Override
    public Work update(long id, Map<String, Object> body) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        if (body.containsKey("title")) w.setTitle(BodyUtils.str(body, "title", w.getTitle()));
        if (body.containsKey("description")) w.setDescription(BodyUtils.str(body, "description", w.getDescription()));
        if (body.containsKey("tags")) w.setTags(BodyUtils.str(body, "tags", w.getTags()));
        if (body.containsKey("cover_path")) w.setCoverPath(BodyUtils.str(body, "cover_path", w.getCoverPath()));
        if (body.containsKey("subtitle_enabled")) w.setSubtitleEnabled(BodyUtils.boolVal(body, "subtitle_enabled", w.getSubtitleEnabled()));
        if (body.containsKey("subtitle_style")) w.setSubtitleStyle(BodyUtils.str(body, "subtitle_style", w.getSubtitleStyle()));
        if (body.containsKey("aspect_ratio")) w.setAspectRatio(BodyUtils.str(body, "aspect_ratio", w.getAspectRatio()));
        w.setUpdatedAt(LocalDateTime.now());
        workMapper.updateById(w);
        return w;
    }

    @Override
    public Work export(long id) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        w.setStatus("RENDERING");
        w.setUpdatedAt(LocalDateTime.now());
        workMapper.updateById(w);
        pipeline.addJob("RENDER", "work", w.getId());
        return w;
    }

    @Override
    public boolean submitReview(long id) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        if (!"READY".equals(w.getStatus())) throw new BizException("仅已完成导出的作品可送审");
        pipeline.addJob("REVIEW", "work", w.getId());
        return true;
    }

    @Override
    public Work publish(long id) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        PublishAccount account = publishService.getAccount();
        if (account == null) throw new BizException("请先绑定发布账号");
        if (!"READY".equals(w.getStatus()) && !"APPROVED".equals(w.getStatus())) {
            throw new BizException("当前状态不可发布");
        }
        // 人工点「发布」：不受全局/房间自动发布开关限制（respectRoomAutoPublish=false）
        pipeline.queuePublish(w, false);
        return w;
    }

    @Override
    public Map<String, Object> suggestTitle(long id) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        Clip c = w.getClipId() == null ? null : clipMapper.selectById(w.getClipId());
        if (c == null) throw new BizException("作品无关联片段，无法生成标题");
        TitleGenerator.Result r = titleGenerator.suggest(c);
        return Map.of("title", r.title(), "kind", r.kind(), "summary", r.summary());
    }

    @Override
    public Map<String, Object> delete(long id, boolean force, boolean deleteFiles) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        long freed = deleteWorkInternal(w, force, deleteFiles);
        if (freed < 0) throw new BizException("已发布作品默认不删除（远端稿件仍在 B 站，此处仅删本地记录）；如确需移除请强制删除");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed_works", 1);
        m.put("freed_bytes", freed);
        return m;
    }

    @Override
    public Map<String, Object> batchDelete(List<Long> ids, boolean force, boolean deleteFiles) {
        int removed = 0, skipped = 0;
        long freed = 0;
        if (ids != null) {
            for (Long id : ids) {
                Work w = id == null ? null : workMapper.selectById(id);
                if (w == null) continue;
                long f = deleteWorkInternal(w, force, deleteFiles);
                if (f < 0) skipped++;
                else { removed++; freed += f; }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed_works", removed);
        m.put("skipped_published", skipped);
        m.put("freed_bytes", freed);
        return m;
    }

    /**
     * 删除单个作品的文件与关联记录。已发布且未 force → 返回 -1（跳过，不删）。
     * 删除范围：成片 output、封面 cover、字幕 subtitles/work_{id}.srt + 审核记录 + 发布任务 + 作品行。
     */
    long deleteWorkInternal(Work w, boolean force, boolean deleteFiles) {
        if ("PUBLISHED".equals(w.getStatus()) && !force) return -1;
        long freed = 0;
        if (deleteFiles) {
            freed += deleteIfPresent(w.getOutputPath());
            freed += deleteIfPresent(w.getCoverPath());
            String dir = settings.getStr("media_dir", "./media");
            freed += deleteIfPresent(dir + "/subtitles/work_" + w.getId() + ".srt");
        }
        reviewRecordMapper.delete(Wrappers.<ReviewRecord>lambdaQuery().eq(ReviewRecord::getWorkId, w.getId()));
        publishTaskMapper.delete(Wrappers.<PublishTask>lambdaQuery().eq(PublishTask::getWorkId, w.getId()));
        workMapper.deleteById(w.getId());
        return freed;
    }

    private static long deleteIfPresent(String path) {
        if (path == null || path.isBlank()) return 0;
        File f = new File(path);
        if (!f.isFile()) return 0;
        long len = f.length();
        return f.delete() ? len : 0;
    }
}
