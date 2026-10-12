package com.aiqiepian.engine.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 斗鱼直播间开放接口（仅开播检测）。
 * open.douyucdn.cn/api/RoomApi/room/{id} 匿名可用：data.room_status "1"=直播中，data.room_name=直播标题。
 * 注意：斗鱼真实录制需要签名取流（未支持），本类只用于监控检测链路。
 */
public final class DouyuApi {

    private static final Logger log = LoggerFactory.getLogger(DouyuApi.class);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DouyuApi() {
    }

    /**
     * GET /api/RoomApi/room/{id}
     * @return [room_status(1=直播中), room_name]；请求/解析失败返回 null
     */
    public static Object[] getRoomInfo(String roomId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://open.douyucdn.cn/api/RoomApi/room/" + roomId))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.douyu.com/")
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("douyu api http {} room={}", resp.statusCode(), roomId);
                return null;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            if (root.path("error").asInt(-1) != 0) {
                log.warn("douyu api error={} room={} data={}", root.path("error").asInt(-1), roomId, root.path("data"));
                return null;
            }
            JsonNode data = root.path("data");
            int status = "1".equals(data.path("room_status").asText("0")) ? 1 : 0;
            String name = data.path("room_name").asText("");
            return new Object[]{status, name};
        } catch (Exception e) {
            log.warn("douyu RoomApi 失败 room={}: {}", roomId, e.toString());
            return null;
        }
    }

    /** 移动端 hlsH5Preview 匿名可拉；FLV 变体（.m3u8→.flv）实测不断流 */
    public static String fetchPlayUrl(String roomId) {
        try {
            String t13 = String.valueOf(System.currentTimeMillis());
            String auth = md5Hex(roomId + t13);
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://playweb.douyucdn.cn/lapi/live/hlsH5Preview/" + roomId))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15")
                    .header("Referer", "https://m.douyu.com/" + roomId)
                    .header("rid", roomId)
                    .header("time", t13)
                    .header("auth", auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "rid=" + roomId + "&did=10000000000000000000000000003306", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("douyu hlsH5Preview http {} room={}", resp.statusCode(), roomId);
                return null;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            if (root.path("error").asInt(-1) != 0) {
                log.warn("douyu hlsH5Preview error={} room={} msg={}",
                        root.path("error").asInt(-1), roomId, root.path("msg").asText(""));
                return null;
            }
            String base = root.path("data").path("rtmp_url").asText("");
            String live = root.path("data").path("rtmp_live").asText("");
            if (base.isEmpty() || live.isEmpty()) return null;
            return base + "/" + live.replace(".m3u8", ".flv");
        } catch (Exception e) {
            log.warn("douyu hlsH5Preview 失败 room={}: {}", roomId, e.toString());
            return null;
        }
    }

    static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
