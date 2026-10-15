package com.aiqiepian.service;

import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.Work;

import java.util.List;
import java.util.Map;

/**
 * 管线核心状态迁移（逻辑对照 mock-server server.mjs，controller 与 PipelineOrchestrator 共用）。
 */
public interface PipelineService {

    /** server.mjs startLive：创建 RECORDING 会话 + RECORD job，房间置 RECORDING */
    RecordingSession startLive(LiveRoom room, boolean manual);

    /** server.mjs finishSession：会话收尾 + 房间回 MONITORING + 入队 ANALYZE */
    void finishSession(RecordingSession session);

    /** server.mjs analyzeDone：analyze_status=DONE，落库 3-8 个 clip，高分自动建 work */
    List<Clip> applyAnalysis(RecordingSession session);

    /**
     * 整理存量重复片段（轮播房同一场录播被反复重录时会产生）：按 v2 内容指纹重新比对，
     * 命中重复则删掉后入库的那一条。apply=false 只出报告不动数据。
     * 返回 {scanned, duplicates, deleted, protected, undecided, recomputed, apply, groups[]}。
     */
    Map<String, Object> dedupSweep(Long roomId, boolean apply);

    /** server.mjs makeWork：建 DRAFT 作品并入队 RENDER job */
    Work makeWork(Clip clip);

    /**
     * 手工拼接：把**同一场录像**里的若干片段按时间顺序拼成一支作品（片段分析页勾选后触发）。
     * 复用自动高光集锦的产物机制（work.reel_segments → RealRenderer 逐段切段 concat）。
     */
    Work makeStitchWork(java.util.List<Clip> clips);

    /** server.mjs renderDone：READY + 产物路径 + 入队 REVIEW */
    void renderDone(Work work);

    /** server.mjs queuePublish：查重后建 QUEUED 发布任务 + PUBLISH job，作品置 PUBLISHING。
     *  自动触发路径（审核通过 → 入队）：额外受房间级 auto_publish 开关约束，
     *  不满足时不入队并返回 null。 */
    PublishTask queuePublish(Work work);

    /** respectRoomAutoPublish=false：人工点「发布」按钮路径，不受房间开关限制（仍需调用方自行校验账号） */
    PublishTask queuePublish(Work work, boolean respectRoomAutoPublish);

    /** 自动入队三门条件：settings.auto_publish(全局) && 房间 auto_publish && 发布账号已绑定 */
    boolean autoPublishAllowed(Work work);

    PipelineJob addJob(String jobType, String entityType, long entityId);

    /** 该实体当前未完成/未跳过的 job（mock jobOf 语义） */
    PipelineJob activeJob(String jobType, String entityType, long entityId);
}
