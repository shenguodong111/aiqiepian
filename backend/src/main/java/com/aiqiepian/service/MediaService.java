package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;

import java.io.File;
import java.util.List;
import java.util.Map;

public interface MediaService {

    /** category: recordings/clips/subtitles/covers/works */
    PageResult<Map<String, Object>> list(String category, Integer page, Integer size);

    /**
     * 素材库按分类批量删除（ids 为该分类行的 ref.id）。
     * recordings：删原始录制文件并清掉会话的文件路径引用（会话记录保留）；
     * clips / works：走各自的删除语义（级联产物 + 已发布默认拒删）；
     * subtitles / covers：仅删对应字幕 / 封面文件。
     * @return removed / freed_bytes / skipped_published
     */
    Map<String, Object> deleteSelected(String category, List<Long> ids, boolean force);

    Map<String, Object> stats();

    /**
     * 解析可播放/预览的真实文件（路径来自 DB，不做任意路径拼接，天然防目录穿越）。
     * @param type session（录制原始）/ work（成片）/ clip（片段：优先成片，回落原始录制）/ cover（封面）
     * @return 磁盘上存在的文件；不存在返回 null
     */
    File resolveStream(String type, long id);

    /** 清理已发布成片的原始录制（本期仅移除记录，不真删盘） */
    Map<String, Object> cleanup(String scope);

    /**
     * 一键清理冗余：每个会话仅保留评分最高的前 keepPerSession 个片段（多余片段连同其作品、审核/发布记录、磁盘文件一并删除），
     * 可选扫描 media 目录删除数据库已不引用的孤儿文件。已发布(PUBLISHED)作品及其文件一律保留、绝不动；房间/会话记录不删。
     * @return removed_clips / removed_works / removed_orphans / freed_bytes / skipped_published
     */
    Map<String, Object> cleanupRedundant(Integer keepPerSession, boolean deleteFiles, boolean purgeOrphans);
}
