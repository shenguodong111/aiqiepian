package com.aiqiepian.engine.real;

import com.aiqiepian.entity.LiveRoom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站直播间开放接口封装（M1 已验证可用的两个 GET 端点）。
 * 使用 JDK HttpClient，不引入额外依赖。
 */
public final class BilibiliApi {

    private static final Logger log = LoggerFactory.getLogger(BilibiliApi.class);

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final Pattern TAIL_DIGITS = Pattern.compile("(\\d+)\\D*$");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BilibiliApi() {
    }

    /** 从房间 URL（https://live.bilibili.com/6）或纯数字提取直播间号 */
    public static String extractRoomId(LiveRoom room) {
        if (room == null) return null;
        String url = room.getRoomUrl();
        if (url == null || url.isBlank()) return null;
        Matcher m = TAIL_DIGITS.matcher(url.trim());
        return m.find() ? m.group(1) : null;
    }

    /**
     * GET /room/v1/Room/get_info?room_id={id}
     * @return [live_status(1=直播中), title]；请求/解析失败返回 null
     */
    public static Object[] getRoomInfo(String roomId) {
        try {
            JsonNode data = getJson(
                    "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId,
                    "https://live.bilibili.com/" + roomId);
            if (data == null) return null;
            int liveStatus = data.path("live_status").asInt(0);
            String title = data.path("title").asText("");
            return new Object[]{liveStatus, title};
        } catch (Exception e) {
            log.warn("bilibili get_info 失败 room={}: {}", roomId, e.toString());
            return null;
        }
    }

    /**
     * GET /room/v1/Room/playUrl?cid={id}&qn=10000&platform=web → data.durl[0].url（FLV 直播流）。
     * @return 流地址；失败返回 null
     */
    public static String fetchPlayUrl(String roomId) {
        try {
            JsonNode data = getJson(
                    "https://api.live.bilibili.com/room/v1/Room/playUrl?cid=" + roomId + "&qn=10000&platform=web",
                    "https://live.bilibili.com/" + roomId);
            if (data == null) return null;
            String url = data.path("durl").path(0).path("url").asText("");
            return url.isEmpty() ? null : url;
        } catch (Exception e) {
            log.warn("bilibili playUrl 失败 room={}: {}", roomId, e.toString());
            return null;
        }
    }

    private static JsonNode getJson(String url, String referer) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", UA)
                .header("Referer", referer)
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("bilibili api http {} url={}", resp.statusCode(), url);
            return null;
        }
        JsonNode root = MAPPER.readTree(resp.body());
        if (root.path("code").asInt(-1) != 0) {
            log.warn("bilibili api code={} url={} message={}",
                    root.path("code").asInt(-1), url, root.path("message").asText(""));
            return null;
        }
        return root.path("data");
    }
}
