package com.aiqiepian.service.impl;

import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.service.ViewCountService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放量同步（M4-3）：公开接口 web-interface/view 拉取稿件统计，无需 Cookie。
 * 失败静默记日志（单条失败不影响整轮）。@Scheduled 每 10 分钟一轮 + 手动触发端点。
 */
@Service
@RequiredArgsConstructor
public class ViewCountServiceImpl implements ViewCountService {

    private static final Logger log = LoggerFactory.getLogger(ViewCountServiceImpl.class);
    private static final String VIEW_API = "https://api.bilibili.com/x/web-interface/view?bvid=";

    private final PublishTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 每 10 分钟自动一轮（返回值被调度器忽略，仅供手动端点复用） */
    @Scheduled(fixedRate = 600000)
    public Map<String, Object> scheduledSync() {
        return syncAll();
    }

    @Override
    public Map<String, Object> syncAll() {
        int synced = 0;
        int failed = 0;
        List<PublishTask> tasks = taskMapper.selectList(Wrappers.<PublishTask>lambdaQuery()
                .eq(PublishTask::getStatus, "PUBLISHED")
                .isNotNull(PublishTask::getRemoteId)
                .orderByAsc(PublishTask::getId));
        for (PublishTask p : tasks) {
            String bv = p.getRemoteId();
            // 仅真实 BV 稿件；mock 状态机产的 BV1mock* 不打真实 API
            if (bv == null || !bv.startsWith("BV") || bv.startsWith("BV1mock")) continue;
            try {
                JsonNode stat = fetchStat(bv);
                LocalDateTime now = LocalDateTime.now();
                taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                        .set(PublishTask::getViewCount, stat.path("view").asInt(0))
                        .set(PublishTask::getLikeCount, stat.path("like").asInt(0))
                        .set(PublishTask::getDanmakuCount, stat.path("danmaku").asInt(0))
                        .set(PublishTask::getViewSyncedAt, now)
                        .eq(PublishTask::getId, p.getId()));
                synced++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed++;
                break;
            } catch (Exception e) {
                // 失败静默：只记日志，保留旧值
                failed++;
                log.warn("播放量同步失败 task={} bvid={}: {}", p.getId(), bv, e.toString());
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("synced", synced);
        r.put("failed", failed);
        return r;
    }

    private JsonNode fetchStat(String bvid) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(VIEW_API + bvid))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json, text/plain, */*")
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("bili code=" + code + " msg=" + root.path("message").asText(""));
        }
        JsonNode stat = root.path("data").path("stat");
        if (stat.isMissingNode()) throw new IllegalStateException("响应缺少 data.stat");
        return stat;
    }
}
