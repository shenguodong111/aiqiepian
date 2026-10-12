package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.RecordingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecordingServiceImpl implements RecordingService {

    private final RecordingSessionMapper sessionMapper;
    private final LiveRoomMapper roomMapper;
    private final PipelineService pipeline;

    @Override
    public PageResult<RecordingSession> list(String status, Long roomId, Integer page, Integer size) {
        var q = Wrappers.<RecordingSession>lambdaQuery().orderByDesc(RecordingSession::getId);
        if (roomId != null) q.eq(RecordingSession::getRoomId, roomId);
        List<RecordingSession> list = sessionMapper.selectList(q);
        if ("active".equals(status)) {
            list = list.stream().filter(s -> "RECORDING".equals(s.getStatus())).toList();
        } else if ("history".equals(status)) {
            list = list.stream().filter(s -> !"RECORDING".equals(s.getStatus())).toList();
        }
        return PageResult.of(list, page, size);
    }

    @Override
    public RecordingSession manualRecord(Long roomId) {
        LiveRoom room = roomId == null ? null : roomMapper.selectById(roomId);
        if (room == null) throw new BizException("直播间不存在");
        if ("RECORDING".equals(room.getStatus())) throw new BizException("该直播间已有进行中的录制");
        return pipeline.startLive(room, true);
    }

    @Override
    public RecordingSession stop(long id) {
        RecordingSession s = sessionMapper.selectById(id);
        if (s == null || !"RECORDING".equals(s.getStatus())) throw new BizException("会话不在录制中");
        s.setDurationSec(s.getRecordedSec());
        pipeline.finishSession(s);
        return s;
    }

    @Override
    public boolean analyze(long id) {
        RecordingSession s = sessionMapper.selectById(id);
        if (s == null) throw new BizException(404, "不存在");
        s.setAnalyzeStatus("PENDING");
        sessionMapper.updateById(s);
        pipeline.addJob("ANALYZE", "session", s.getId());
        return true;
    }
}
