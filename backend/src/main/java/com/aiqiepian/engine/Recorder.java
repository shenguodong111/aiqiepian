package com.aiqiepian.engine;

import com.aiqiepian.entity.RecordingSession;

/** 环节 02 录制（需求文档 §7）。真实实现替换：ffmpeg 拉流。 */
public interface Recorder {

    /** 开播即建会话文件；mock 生成假路径与目标时长 */
    default void start(RecordingSession session) {
        start(session, false);
    }

    void start(RecordingSession session, boolean manual);

    /** 真实引擎：该平台取流是否已支持录制（mock 默认全支持） */
    default boolean supportsRealRecording(String platform) {
        return true;
    }

    /**
     * 结束会话；mock 按目标时长生成假文件体积。
     * 真实实现应停止录制进程，并把真实 recordedSec / fileSizeBytes 回写到 session。
     */
    void stop(RecordingSession session);

    /** 真实引擎探测：当前已录秒数；返回 -1 表示无真实进度（mock 沿用 tick 累加） */
    default int probeRecordedSec(RecordingSession session) {
        return -1;
    }

    /** 真实引擎探测：磁盘真实字节数；返回 -1 表示沿用 mock 假体积 */
    default long probeFileSizeBytes(RecordingSession session) {
        return -1;
    }

    /** 真实引擎探测：录制进程是否仍存活；默认 true（mock 不受影响），进程已退出（下播/断流）时编排器会结束会话 */
    default boolean probeAlive(RecordingSession session) {
        return true;
    }

    /**
     * 进程退出前收尾所有仍在录制的会话（停 ffmpeg、合并分片、回写时长）。
     * 正常由容器 @PreDestroy 调用；一键停止额外显式调用一次，避免关闭流程被其它环节卡住时
     * 录制分片没收尾而整段丢失。实现必须幂等。
     */
    default void drainForShutdown() {
    }
}
