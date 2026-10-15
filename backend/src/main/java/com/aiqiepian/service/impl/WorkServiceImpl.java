package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.engine.Renderer;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.ReviewRecord;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.ReviewRecordMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.CoverAssetService;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.PublishService;
import com.aiqiepian.service.SettingsService;
import com.aiqiepian.service.TitleGenerator;
import com.aiqiepian.service.WorkService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkServiceImpl implements WorkService {

    private static final Logger log = LoggerFactory.getLogger(WorkServiceImpl.class);

    private final WorkMapper workMapper;
    private final ClipMapper clipMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final PublishTaskMapper publishTaskMapper;
    private final SettingsService settings;
    private final PipelineService pipeline;
    private final PublishService publishService;
    private final TitleGenerator titleGenerator;
    private final Renderer renderer;
    private final CoverAssetService coverAssetService;

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
        boolean titleChanged = false;
        if (body.containsKey("title")) {
            String t = BodyUtils.str(body, "title", w.getTitle());
            titleChanged = t != null && !t.equals(w.getTitle());
            w.setTitle(t);
            // 人工定过的标题要钉住：渲染前的大模型精修不能再把它换掉
            if (titleChanged) w.setTitleEdited(true);
        }
        if (body.containsKey("description")) w.setDescription(BodyUtils.str(body, "description", w.getDescription()));
        if (body.containsKey("tags")) w.setTags(BodyUtils.str(body, "tags", w.getTags()));
        if (body.containsKey("cover_path")) w.setCoverPath(BodyUtils.str(body, "cover_path", w.getCoverPath()));
        if (body.containsKey("subtitle_enabled")) w.setSubtitleEnabled(BodyUtils.boolVal(body, "subtitle_enabled", w.getSubtitleEnabled()));
        if (body.containsKey("subtitle_style")) w.setSubtitleStyle(BodyUtils.str(body, "subtitle_style", w.getSubtitleStyle()));
        if (body.containsKey("aspect_ratio")) w.setAspectRatio(BodyUtils.str(body, "aspect_ratio", w.getAspectRatio()));
        w.setUpdatedAt(LocalDateTime.now());
        workMapper.updateById(w);
        // 封面文字取自标题：审片时改了名要立刻跟上，否则发出去标题和封面各说一套
        if (titleChanged) renderer.buildCover(w);
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
        if (!"READY".equals(w.getStatus()) && !"APPROVED".equals(w.getStatus()) && !"PENDING_REVIEW".equals(w.getStatus())) {
            throw new BizException("当前状态不可发布");
        }
        // 人工点「发布」：不受全局/房间自动发布开关限制（respectRoomAutoPublish=false）
        pipeline.queuePublish(w, false);
        return w;
    }

    @Override
    public Work approve(long id) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        if (!"PENDING_REVIEW".equals(w.getStatus()) && !"READY".equals(w.getStatus())) {
            throw new BizException("仅待审/待送审状态可审批通过");
        }
        w.setStatus("APPROVED");
        w.setUpdatedAt(java.time.LocalDateTime.now());
        workMapper.updateById(w);
        // 审批通过后，若全局自动发布开启且房间也开启，自动入队发布
        if (pipeline.autoPublishAllowed(w)) {
            pipeline.queuePublish(w);
        }
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
    public Work setCoverImage(long id, MultipartFile file) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        String path = coverAssetService.saveImage(file, "covers/manual", "work_" + id);
        deleteIfPresent(w.getCoverImage());
        w.setCoverImage(path);
        w.setUpdatedAt(LocalDateTime.now());
        workMapper.updateById(w);
        renderer.buildCover(w);
        return w;
    }

    @Override
    public Work clearCoverImage(long id) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        deleteIfPresent(w.getCoverImage());
        LocalDateTime now = LocalDateTime.now();
        // updateById 会跳过 null 字段，清空底图必须走显式 SET
        workMapper.update(null, Wrappers.<Work>lambdaUpdate()
                .set(Work::getCoverImage, null)
                .set(Work::getUpdatedAt, now)
                .eq(Work::getId, id));
        w.setCoverImage(null);
        w.setUpdatedAt(now);
        renderer.buildCover(w);
        return w;
    }

    @Override
    public Work rebuildCover(long id) {
        Work w = workMapper.selectById(id);
        if (w == null) throw new BizException(404, "不存在");
        renderer.buildCover(w);
        return w;
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
     * 删除范围：成片 output、封面 cover、手工封面底图、字幕 subtitles/work_{id}.srt + 审核记录 + 发布任务 + 作品行。
     */
    long deleteWorkInternal(Work w, boolean force, boolean deleteFiles) {
        if ("PUBLISHED".equals(w.getStatus()) && !force) return -1;
        long freed = 0;
        if (deleteFiles) {
            freed += deleteIfPresent(w.getOutputPath());
            freed += deleteIfPresent(w.getCoverPath());
            freed += deleteIfPresent(w.getCoverImage());
            String dir = settings.getStr("media_dir", "./media");
            freed += deleteIfPresent(dir + "/subtitles/work_" + w.getId() + ".srt");
        }
        reviewRecordMapper.delete(Wrappers.<ReviewRecord>lambdaQuery().eq(ReviewRecord::getWorkId, w.getId()));
        publishTaskMapper.delete(Wrappers.<PublishTask>lambdaQuery().eq(PublishTask::getWorkId, w.getId()));
        workMapper.deleteById(w.getId());
        // 审计：作品删除不可恢复，必须留下"谁在什么时候删了哪一支、是否连文件一起删"的痕迹
        log.info("审计删除[作品] #{}《{}》状态={} 来源片段={} 连带删除文件={} 回收={} KB",
                w.getId(), w.getTitle(), w.getStatus(), w.getClipId(), deleteFiles, freed / 1024);
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
