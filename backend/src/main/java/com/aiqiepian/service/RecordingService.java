package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.RecordingSession;

public interface RecordingService {

    /** status: active（录制中）/ history（其余）/ 空（全部，倒序） */
    PageResult<RecordingSession> list(String status, Long roomId, Integer page, Integer size);

    RecordingSession manualRecord(Long roomId);

    RecordingSession stop(long id);

    /** 触发分析：置 analyze_status=PENDING 并入队 ANALYZE job */
    boolean analyze(long id);
}
