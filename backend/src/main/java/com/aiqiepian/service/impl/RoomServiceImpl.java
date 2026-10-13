package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.engine.Recorder;
import com.aiqiepian.engine.StreamMonitor;
import com.aiqiepian.engine.model.LiveEvent;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.RoomService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {

    private final LiveRoomMapper roomMapper;
    private final StreamMonitor streamMonitor;
    private final Recorder recorder;
    private final PipelineService pipeline;

    @Override
    public PageResult<LiveRoom> list(String platform, String status, String kw, Integer page, Integer size) {
        var q = Wrappers.<LiveRoom>lambdaQuery();
        if (platform != null && !platform.isEmpty()) q.eq(LiveRoom::getPlatform, platform);
        if (status != null && !status.isEmpty()) q.eq(LiveRoom::getStatus, status);
        if (kw != null && !kw.isEmpty()) {
            q.and(w -> w.like(LiveRoom::getRoomName, kw)
                    .or().like(LiveRoom::getAnchorName, kw)
                    .or().like(LiveRoom::getRoomUrl, kw));
        }
        q.orderByAsc(LiveRoom::getId);
        List<LiveRoom> list = roomMapper.selectList(q);
        return PageResult.of(list, page, size);
    }

    @Override
    public LiveRoom create(Map<String, Object> body) {
        String platform = BodyUtils.str(body, "platform", null);
        String roomUrl = BodyUtils.str(body, "room_url", null);
        if (platform == null || platform.isEmpty() || roomUrl == null || roomUrl.isEmpty()) {
            throw new BizException("参数不完整：platform/room_url 必填");
        }
        LocalDateTime now = LocalDateTime.now();
        LiveRoom room = new LiveRoom();
        room.setPlatform(platform);
        room.setRoomUrl(roomUrl);
        room.setRoomName(BodyUtils.str(body, "room_name", ""));
        room.setAnchorName(BodyUtils.str(body, "anchor_name", ""));
        room.setMonitorEnabled(BodyUtils.boolVal(body, "monitor_enabled", true));
        room.setAutoRecord(BodyUtils.boolVal(body, "auto_record", true));
        room.setAutoPublish(BodyUtils.boolVal(body, "auto_publish", true));
        room.setPublishTags(BodyUtils.str(body, "publish_tags", ""));
        room.setAnalyzeTemplate(BodyUtils.str(body, "analyze_template", "highlight"));
        room.setStatus(Boolean.TRUE.equals(room.getMonitorEnabled()) ? "MONITORING" : "OFF");
        room.setLastLiveAt(null);
        room.setCreatedAt(now);
        room.setUpdatedAt(now);
        roomMapper.insert(room);
        return room;
    }

    @Override
    public LiveRoom update(long id, Map<String, Object> body) {
        LiveRoom room = roomMapper.selectById(id);
        if (room == null) throw new BizException(404, "直播间不存在");
        if (body.containsKey("platform")) room.setPlatform(BodyUtils.str(body, "platform", room.getPlatform()));
        if (body.containsKey("room_url")) room.setRoomUrl(BodyUtils.str(body, "room_url", room.getRoomUrl()));
        if (body.containsKey("room_name")) room.setRoomName(BodyUtils.str(body, "room_name", room.getRoomName()));
        if (body.containsKey("anchor_name")) room.setAnchorName(BodyUtils.str(body, "anchor_name", room.getAnchorName()));
        if (body.containsKey("monitor_enabled")) {
            room.setMonitorEnabled(BodyUtils.boolVal(body, "monitor_enabled", room.getMonitorEnabled()));
            // 编辑弹窗里改监控开关时同步状态（录制中不打断）
            if (Boolean.TRUE.equals(room.getMonitorEnabled())) {
                if ("OFF".equals(room.getStatus())) room.setStatus("MONITORING");
            } else if ("MONITORING".equals(room.getStatus())) {
                room.setStatus("OFF");
            }
        }
        if (body.containsKey("auto_record")) room.setAutoRecord(BodyUtils.boolVal(body, "auto_record", room.getAutoRecord()));
        if (body.containsKey("auto_publish")) room.setAutoPublish(BodyUtils.boolVal(body, "auto_publish", room.getAutoPublish()));
        if (body.containsKey("publish_tags")) room.setPublishTags(BodyUtils.str(body, "publish_tags", room.getPublishTags()));
        if (body.containsKey("analyze_template")) room.setAnalyzeTemplate(BodyUtils.str(body, "analyze_template", room.getAnalyzeTemplate()));
        room.setUpdatedAt(LocalDateTime.now());
        roomMapper.updateById(room);
        return room;
    }

    @Override
    public void delete(long id) {
        LiveRoom room = roomMapper.selectById(id);
        if (room == null) throw new BizException(404, "直播间不存在");
        if ("RECORDING".equals(room.getStatus())) throw new BizException("录制中禁止删除");
        roomMapper.deleteById(id);
    }

    @Override
    public LiveRoom toggle(long id, boolean enabled) {
        LiveRoom room = roomMapper.selectById(id);
        if (room == null) throw new BizException(404, "不存在");
        room.setMonitorEnabled(enabled);
        room.setStatus(enabled ? "MONITORING" : "OFF");
        room.setUpdatedAt(LocalDateTime.now());
        roomMapper.updateById(room);
        return room;
    }

    @Override
    public Map<String, Object> check(long id) {
        LiveRoom room = roomMapper.selectById(id);
        if (room == null) throw new BizException(404, "不存在");
        LiveEvent ev = streamMonitor.check(room, true);
        boolean live = ev.isLive();
        // 开播且该平台支持真实录制才自动起录；否则仅如实返回开播状态
        boolean startable = live && recorder.supportsRealRecording(room.getPlatform());
        if (startable && !"RECORDING".equals(room.getStatus())) {
            pipeline.startLive(room, true);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("live", live);
        if (ev.getTitle() != null) result.put("title", ev.getTitle());
        if (live && !recorder.supportsRealRecording(room.getPlatform())) {
            result.put("note", "该主播正在直播，但本平台真实录制暂未支持（当前支持 B站/斗鱼/虎牙），本次只做开播检测，不自动录制");
        }
        return result;
    }
}
