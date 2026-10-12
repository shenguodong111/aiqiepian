package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.ConfigSyncService;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 跨机迁移（需求文档 §6 之外的运维能力）：配置导出/导入 JSON。 */
@Service
@RequiredArgsConstructor
public class ConfigSyncServiceImpl implements ConfigSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConfigSyncServiceImpl.class);

    private static final String KIND = "aiqiepian-config";
    private static final int FORMAT_VERSION = 1;
    /** 历史条目里为跨机重映射而附加的临时键，反序列化成实体前必须剥掉 */
    private static final List<String> REMAP_KEYS = List.of("room_url", "src_id", "src_session_id", "src_clip_id");

    private final LiveRoomMapper roomMapper;
    private final RecordingSessionMapper sessionMapper;
    private final ClipMapper clipMapper;
    private final WorkMapper workMapper;
    private final SettingsService settings;
    private final ObjectMapper json;

    @Override
    public Map<String, Object> exportConfig(boolean includeHistory) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", KIND);
        payload.put("version", FORMAT_VERSION);
        payload.put("exported_at", LocalDateTime.now().toString());
        payload.put("rooms", exportRooms());
        payload.put("settings", exportSettings());
        if (includeHistory) {
            payload.put("history", exportHistory());
        }
        return payload;
    }

    private List<Map<String, Object>> exportRooms() {
        return roomMapper.selectList(Wrappers.<LiveRoom>lambdaQuery().orderByAsc(LiveRoom::getId)).stream()
                .map(this::roomEntry)
                .toList();
    }

    private Map<String, Object> roomEntry(LiveRoom r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", r.getPlatform());
        m.put("room_url", r.getRoomUrl());
        m.put("room_name", r.getRoomName());
        m.put("anchor_name", r.getAnchorName());
        m.put("live_title", r.getLiveTitle());
        m.put("monitor_enabled", r.getMonitorEnabled());
        m.put("auto_record", r.getAutoRecord());
        m.put("auto_publish", r.getAutoPublish());
        m.put("analyze_template", r.getAnalyzeTemplate());
        return m;
    }

    private Map<String, Object> exportSettings() {
        Map<String, Object> m = new LinkedHashMap<>(settings.getAll());
        SettingsServiceImpl.SENSITIVE_KEYS.forEach(m::remove);
        m.remove(SettingsServiceImpl.KEY_PUBLISH_ACCOUNT);
        m.remove("engine_mode");
        return m;
    }

    private Map<String, Object> exportHistory() {
        Map<Long, String> roomUrlById = new LinkedHashMap<>();
        roomMapper.selectList(Wrappers.<LiveRoom>lambdaQuery()).forEach(r -> roomUrlById.put(r.getId(), r.getRoomUrl()));

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("recordings", sessionMapper.selectList(Wrappers.<RecordingSession>lambdaQuery()
                        .orderByAsc(RecordingSession::getId)).stream()
                .map(s -> withRoom(toMap(s), roomUrlById.get(s.getRoomId())))
                .toList());
        Map<Long, RecordingSession> sessionById = new LinkedHashMap<>();
        sessionMapper.selectList(Wrappers.<RecordingSession>lambdaQuery()).forEach(s -> sessionById.put(s.getId(), s));
        history.put("clips", clipMapper.selectList(Wrappers.<Clip>lambdaQuery().orderByAsc(Clip::getId)).stream()
                .map(c -> {
                    Map<String, Object> m = toMap(c);
                    m.put("src_session_id", c.getSessionId());
                    RecordingSession s = sessionById.get(c.getSessionId());
                    return withRoom(m, s == null ? null : roomUrlById.get(s.getRoomId()));
                })
                .toList());
        Map<Long, Clip> clipById = new LinkedHashMap<>();
        clipMapper.selectList(Wrappers.<Clip>lambdaQuery()).forEach(c -> clipById.put(c.getId(), c));
        history.put("works", workMapper.selectList(Wrappers.<Work>lambdaQuery().orderByAsc(Work::getId)).stream()
                .map(w -> {
                    Map<String, Object> m = toMap(w);
                    m.put("src_clip_id", w.getClipId());
                    Clip c = clipById.get(w.getClipId());
                    RecordingSession s = c == null ? null : sessionById.get(c.getSessionId());
                    return withRoom(m, s == null ? null : roomUrlById.get(s.getRoomId()));
                })
                .toList());
        return history;
    }

    private Map<String, Object> toMap(Object entity) {
        Map<String, Object> m = json.convertValue(entity, Map.class);
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("src_id", m.get("id"));
        out.remove("id");
        out.remove("room_id");
        return out;
    }

    private Map<String, Object> withRoom(Map<String, Object> m, String roomUrl) {
        m.put("room_url", roomUrl);
        return m;
    }

    @Override
    public Map<String, Object> importConfig(Map<String, Object> payload) {
        if (payload == null || !KIND.equals(payload.get("kind"))) {
            throw new BizException("文件格式不对：这不是本工具导出的配置 JSON");
        }
        Object ver = payload.get("version");
        if (ver instanceof Number n && n.intValue() > FORMAT_VERSION) {
            throw new BizException("配置版本过新（文件 v" + n.intValue() + "，本机 v" + FORMAT_VERSION + "），请先把程序升级到同版本再导入");
        }

        Map<Long, String> urlByRoomId = new LinkedHashMap<>();
        roomMapper.selectList(Wrappers.<LiveRoom>lambdaQuery()).forEach(r -> urlByRoomId.put(r.getId(), r.getRoomUrl()));
        Map<String, Long> roomIdByUrl = new LinkedHashMap<>();
        urlByRoomId.forEach((id, url) -> roomIdByUrl.put(norm(url), id));

        Map<String, Object> result = new LinkedHashMap<>();
        int[] roomCounts = importRooms(listOfMaps(payload.get("rooms")), roomIdByUrl);
        result.put("rooms_created", roomCounts[0]);
        result.put("rooms_updated", roomCounts[1]);
        result.put("settings_updated", importSettings(payload.get("settings")));

        Object history = payload.get("history");
        if (history instanceof Map<?, ?>) {
            Map<String, Object> h = asMap(history);
            importHistory(h, roomIdByUrl, result);
        }
        log.info("配置导入完成：{} -> 房间 新增 {} / 覆盖 {}", payload.get("exported_at"),
                result.get("rooms_created"), result.get("rooms_updated"));
        return result;
    }

    private int[] importRooms(List<Map<String, Object>> rooms, Map<String, Long> roomIdByUrl) {
        int created = 0;
        int updated = 0;
        for (Map<String, Object> body : rooms) {
            String url = norm(str(body.get("room_url")));
            if (url.isEmpty()) {
                continue;
            }
            LiveRoom existing = roomMapper.selectById(roomIdByUrl.get(url));
            LocalDateTime now = LocalDateTime.now();
            if (existing != null) {
                applyEditable(existing, body);
                existing.setUpdatedAt(now);
                roomMapper.updateById(existing);
                updated++;
            } else {
                LiveRoom room = new LiveRoom();
                room.setRoomUrl(str(body.get("room_url")));
                room.setPlatform(strOr(body.get("platform"), "bilibili"));
                room.setLiveTitle(str(body.get("live_title")));
                applyEditable(room, body);
                boolean monitor = Boolean.TRUE.equals(room.getMonitorEnabled());
                room.setStatus(monitor ? "MONITORING" : "OFF");
                room.setCreatedAt(now);
                room.setUpdatedAt(now);
                roomMapper.insert(room);
                roomIdByUrl.put(norm(room.getRoomUrl()), room.getId());
                created++;
            }
        }
        return new int[]{created, updated};
    }

    /** 只覆盖可配置字段；status/last_live_at 是本机运行态，导入不动 */
    private void applyEditable(LiveRoom room, Map<String, Object> body) {
        if (body.get("platform") != null) room.setPlatform(str(body.get("platform")));
        if (body.get("room_name") != null) room.setRoomName(str(body.get("room_name")));
        if (body.get("anchor_name") != null) room.setAnchorName(str(body.get("anchor_name")));
        if (body.get("live_title") != null) room.setLiveTitle(str(body.get("live_title")));
        room.setMonitorEnabled(bool(body.get("monitor_enabled"), room.getMonitorEnabled() == null || room.getMonitorEnabled()));
        room.setAutoRecord(bool(body.get("auto_record"), room.getAutoRecord() == null || room.getAutoRecord()));
        room.setAutoPublish(bool(body.get("auto_publish"), room.getAutoPublish() == null || room.getAutoPublish()));
        if (body.get("analyze_template") != null) room.setAnalyzeTemplate(str(body.get("analyze_template")));
    }

    private int importSettings(Object raw) {
        Map<String, Object> patch = new LinkedHashMap<>(asMap(raw));
        SettingsServiceImpl.SENSITIVE_KEYS.forEach(patch::remove);
        patch.remove(SettingsServiceImpl.KEY_PUBLISH_ACCOUNT);
        patch.remove("engine_mode");
        if (patch.isEmpty()) {
            return 0;
        }
        settings.updateAll(patch);
        return patch.size();
    }

    private void importHistory(Map<String, Object> history, Map<String, Long> roomIdByUrl, Map<String, Object> result) {
        Map<Long, Long> sessionMap = new LinkedHashMap<>();
        Map<Long, Long> clipMap = new LinkedHashMap<>();
        int[] rc = importRecordings(listOfMaps(history.get("recordings")), roomIdByUrl, sessionMap);
        int[] cc = importClips(listOfMaps(history.get("clips")), roomIdByUrl, sessionMap, clipMap);
        int[] wc = importWorks(listOfMaps(history.get("works")), clipMap);
        result.put("recordings_created", rc[0]);
        result.put("recordings_skipped", rc[1]);
        result.put("clips_created", cc[0]);
        result.put("clips_skipped", cc[1]);
        result.put("works_created", wc[0]);
        result.put("works_skipped", wc[1]);
    }

    private int[] importRecordings(List<Map<String, Object>> rows, Map<String, Long> roomIdByUrl, Map<Long, Long> sessionMap) {
        int created = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            Long roomId = roomIdByUrl.get(norm(str(row.get("room_url"))));
            if (roomId == null) {
                skipped++;
                continue;
            }
            RecordingSession fresh = toEntity(row, RecordingSession.class);
            RecordingSession existing = sessionMapper.selectOne(Wrappers.<RecordingSession>lambdaQuery()
                    .eq(RecordingSession::getRoomId, roomId)
                    .eq(RecordingSession::getStartedAt, fresh.getStartedAt())
                    .last("LIMIT 1"));
            if (existing != null) {
                sessionMap.put(srcId(row), existing.getId());
                skipped++;
                continue;
            }
            fresh.setId(null);
            fresh.setRoomId(roomId);
            sessionMapper.insert(fresh);
            sessionMap.put(srcId(row), fresh.getId());
            created++;
        }
        return new int[]{created, skipped};
    }

    private int[] importClips(List<Map<String, Object>> rows, Map<String, Long> roomIdByUrl,
                              Map<Long, Long> sessionMap, Map<Long, Long> clipMap) {
        int created = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            Long sessionId = sessionMap.get(longOf(row.get("src_session_id")));
            if (sessionId == null) {
                RecordingSession own = sessionFor(row, roomIdByUrl);
                if (own == null) {
                    skipped++;
                    continue;
                }
                sessionId = own.getId();
            }
            Clip fresh = toEntity(row, Clip.class);
            Clip existing = clipMapper.selectOne(Wrappers.<Clip>lambdaQuery()
                    .eq(Clip::getSessionId, sessionId)
                    .eq(Clip::getStartSec, fresh.getStartSec())
                    .eq(Clip::getEndSec, fresh.getEndSec())
                    .last("LIMIT 1"));
            if (existing != null) {
                clipMap.put(srcId(row), existing.getId());
                skipped++;
                continue;
            }
            fresh.setId(null);
            fresh.setSessionId(sessionId);
            fresh.setRoomId(sessionMapper.selectById(sessionId).getRoomId());
            clipMapper.insert(fresh);
            clipMap.put(srcId(row), fresh.getId());
            created++;
        }
        return new int[]{created, skipped};
    }

    private int[] importWorks(List<Map<String, Object>> rows, Map<Long, Long> clipMap) {
        int created = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            Long clipId = clipMap.get(longOf(row.get("src_clip_id")));
            Work fresh = toEntity(row, Work.class);
            if (clipId == null) {
                Clip own = fresh.getClipId() == null ? null
                        : clipMapper.selectOne(Wrappers.<Clip>lambdaQuery().eq(Clip::getId, fresh.getClipId()).last("LIMIT 1"));
                if (own == null) {
                    skipped++;
                    continue;
                }
                clipId = own.getId();
            }
            Work existing = workMapper.selectOne(Wrappers.<Work>lambdaQuery()
                    .eq(Work::getClipId, clipId)
                    .eq(Work::getTitle, fresh.getTitle())
                    .last("LIMIT 1"));
            if (existing != null) {
                skipped++;
                continue;
            }
            fresh.setId(null);
            fresh.setClipId(clipId);
            workMapper.insert(fresh);
            created++;
        }
        return new int[]{created, skipped};
    }

    /** 房间在本机不存在（该条目无法定位会话）时跳过，避免插入悬空片段 */
    private RecordingSession sessionFor(Map<String, Object> row, Map<String, Long> roomIdByUrl) {
        Long roomId = roomIdByUrl.get(norm(str(row.get("room_url"))));
        if (roomId == null) {
            return null;
        }
        return sessionMapper.selectOne(Wrappers.<RecordingSession>lambdaQuery()
                .eq(RecordingSession::getRoomId, roomId)
                .orderByAsc(RecordingSession::getId)
                .last("LIMIT 1"));
    }

    private <T> T toEntity(Map<String, Object> row, Class<T> type) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        REMAP_KEYS.forEach(copy::remove);
        return json.convertValue(copy, type);
    }

    private Long srcId(Map<String, Object> row) {
        return longOf(row.get("src_id"));
    }

    private Long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : new LinkedHashMap<>();
    }

    private List<Map<String, Object>> listOfMaps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(item -> asMap(item)).toList();
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String strOr(Object v, String def) {
        String s = str(v);
        return s.isEmpty() ? def : s;
    }

    private boolean bool(Object v, boolean def) {
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    private String norm(String url) {
        return url == null ? "" : url.trim();
    }
}
