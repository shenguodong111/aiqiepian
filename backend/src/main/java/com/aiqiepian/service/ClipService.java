package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.Work;

import java.util.List;
import java.util.Map;

public interface ClipService {

    PageResult<Clip> list(Long sessionId, Long roomId, Integer minScore, String status, Integer page, Integer size);

    /** 编辑起止秒并重算时长 */
    Clip updateRange(long id, Integer startSec, Integer endSec);

    Clip discard(long id);

    /** 丢弃为软删，可恢复回 PENDING（FR-P4-03） */
    Clip restore(long id);

    Work makeWork(long id);

    List<Work> batchMakeWork(List<Long> ids);

    /**
     * 手工拼接：把勾选的多个片段拼成**一支**作品。要求同属一场录像、时间区间互不重叠，
     * 至少两段；片段状态置为已成片，作品直接进入成片队列。
     */
    Work stitch(List<Long> ids);

    /**
     * 删除片段：级联删除其派生作品（复用作品删除语义：文件+审核/发布记录），再删片段自身文件与记录。
     * 所属会话的原始录制文件不动（多片段共享）。已发布作品未 force 时该片段计入 skipped 不删。
     * 返回 {removed_clips, removed_works, skipped_published, freed_bytes}。
     */
    Map<String, Object> delete(long id, boolean force, boolean deleteFiles);

    Map<String, Object> batchDelete(List<Long> ids, boolean force, boolean deleteFiles);
}
