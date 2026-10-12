package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Work;

import java.util.List;
import java.util.Map;

public interface WorkService {

    PageResult<Work> list(String status, Integer page, Integer size);

    /** POST /works body {clipId,...}（与 mock 相同：仅按 clipId 建作品，其余字段忽略） */
    Work createFromClip(Map<String, Object> body);

    Work update(long id, Map<String, Object> body);

    /** 导出成片：status→RENDERING，入队 RENDER job */
    Work export(long id);

    /** 送审：仅 READY 可送审，入队 REVIEW job */
    boolean submitReview(long id);

    /** 手动发布：需已绑定账号且状态 READY/APPROVED */
    Work publish(long id);

    /** 智能标题建议：{title, kind, summary}，不落库（FR-P5-05） */
    Map<String, Object> suggestTitle(long id);

    /**
     * 删除作品：连带成片/封面/字幕文件与审核、发布记录。
     * 已发布(PUBLISHED)默认拒绝（远端稿件仍在 B 站，删的只是本地记录），force=true 才删。
     * 返回 {removed_works, freed_bytes}。
     */
    Map<String, Object> delete(long id, boolean force, boolean deleteFiles);

    /** 批量删除作品，语义同 {@link #delete}；单个已发布未 force 不中断其余，计入 skipped_published。 */
    Map<String, Object> batchDelete(List<Long> ids, boolean force, boolean deleteFiles);
}
