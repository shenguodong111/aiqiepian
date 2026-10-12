package com.aiqiepian.engine.real;

import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.engine.StreamMonitor;
import com.aiqiepian.engine.model.LiveEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实监控（M1）：按平台分发开播检测，附带直播标题。
 * bilibili=get_info；douyu=RoomApi/room；huya=直播页 flv 探测。三者录制均已支持（见 RealRecorder）。
 * 其它平台（含抖音）真实模式下视为未开播。
 * 带 TTL 缓存（默认 5s），避免 3s tick 对同一房间重复打接口；manual=true 强制刷新。
 */
@Component
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RealStreamMonitor implements StreamMonitor {

    private static final Logger log = LoggerFactory.getLogger(RealStreamMonitor.class);
    private static final long CACHE_TTL_MS = 5_000L;

    private record Cached(long atMs, LiveEvent event) {
    }

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public LiveEvent check(LiveRoom room, boolean manual) {
        String platform = room.getPlatform() == null ? "" : room.getPlatform().toLowerCase();
        String roomId = "huya".equals(platform)
                ? HuyaApi.resolveRoomId(HuyaApi.extractRoomToken(room))
                : BilibiliApi.extractRoomId(room);
        if (roomId == null) return new LiveEvent(false);

        long now = System.currentTimeMillis();
        Cached cached = cache.get(room.getId());
        if (!manual && cached != null && now - cached.atMs() < CACHE_TTL_MS) {
            return cached.event();
        }

        LiveEvent event;
        switch (platform) {
            case "bilibili" -> {
                Object[] info = BilibiliApi.getRoomInfo(roomId);
                if (info == null) {
                    log.warn("真实监控查询失败，按未开播处理 room={}", roomId);
                    event = new LiveEvent(false);
                } else {
                    String title = (String) info[1];
                    event = new LiveEvent(((Integer) info[0]) == 1, title == null || title.isEmpty() ? null : title);
                }
            }
            case "douyu" -> {
                Object[] info = DouyuApi.getRoomInfo(roomId);
                if (info == null) {
                    log.warn("douyu 开播检测失败，按未开播处理 room={}", roomId);
                    event = new LiveEvent(false);
                } else {
                    String title = (String) info[1];
                    event = new LiveEvent(((Integer) info[0]) == 1, title == null || title.isEmpty() ? null : title);
                }
            }
            case "huya" -> {
                Object[] info = HuyaApi.getRoomInfo(roomId);
                if (info == null) {
                    log.warn("huya 开播检测失败，按未开播处理 room={}", roomId);
                    event = new LiveEvent(false);
                } else {
                    String title = (String) info[1];
                    event = new LiveEvent(((Integer) info[0]) == 1, title == null || title.isEmpty() ? null : title);
                }
            }
            default -> {
                log.info("真实监控暂未支持平台 {}（按未开播处理）room={}", platform, roomId);
                event = new LiveEvent(false);
            }
        }
        cache.put(room.getId(), new Cached(now, event));
        return event;
    }
}
