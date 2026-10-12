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
 * 虎牙直播间接口封装（profileRoom showSecret=1，小程序通道，匿名可用）。
 * 返回的 sFlvAntiCode 为服务端预签名；边缘节点对匿名长连接约 5~60s 会切断，
 * 由 RealRecorder 的重连监管器负责续录（每次重连重新取新签名）。
 */
public final class HuyaApi {

    private static final Logger log = LoggerFactory.getLogger(HuyaApi.class);
    private static final String WX_UA = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))";
    private static final String WX_REFERER = "https://servicewechat.com/wx74767bf0b684f7d3/301/page-frame.html";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    /** www.huya.com/{id-or-alias}，alias 允许字母数字-_ */
    private static final Pattern TOKEN = Pattern.compile("huya\\.com/([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_ROOM = Pattern.compile("\"profileRoom\"\\s*:\\s*\"?(\\d+)");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HuyaApi() {
    }

    /** 从房间 URL 提取房间号或主播拼音名（虎牙 URL 尾段可能是字母 alias） */
    public static String extractRoomToken(LiveRoom room) {
        if (room == null || room.getRoomUrl() == null) return null;
        Matcher m = TOKEN.matcher(room.getRoomUrl().trim());
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    /** alias → 数字房间号（profileRoom 接口对 alias 也支持，但统一解析更稳） */
    public static String resolveRoomId(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.chars().allMatch(Character::isDigit)) return token;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.huya.com/" + token))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", BROWSER_UA)
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            Matcher m = PROFILE_ROOM.matcher(resp.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            log.warn("huya alias 解析失败 token={}: {}", token, e.toString());
            return null;
        }
    }

    private static JsonNode profileRoomData(String roomId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=" + roomId + "&showSecret=1"))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", WX_UA)
                    .header("xweb_xhr", "1")
                    .header("referer", WX_REFERER)
                    .header("accept-language", "zh-CN,zh;q=0.9")
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("huya profileRoom http {} room={}", resp.statusCode(), roomId);
                return null;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.path("data");
            return data.isMissingNode() || data.isNull() ? null : data;
        } catch (Exception e) {
            log.warn("huya profileRoom 失败 room={}: {}", roomId, e.toString());
            return null;
        }
    }

    /**
     * @return [live(1/0), title]；失败返回 null
     */
    public static Object[] getRoomInfo(String roomId) {
        JsonNode data = profileRoomData(roomId);
        if (data == null) return null;
        int live = "ON".equalsIgnoreCase(data.path("realLiveStatus").asText("")) ? 1 : 0;
        String title = data.path("liveData").path("introduction").asText("");
        if (title.isBlank()) title = data.path("profileInfo").path("nick").asText("");
        return new Object[]{live, title};
    }

    /**
     * 取 FLV 播放地址（TX→HS→AL 优先）。
     * @return 完整 flv URL；未开播/解析失败返回 null
     */
    public static String fetchPlayUrl(String roomId) {
        JsonNode data = profileRoomData(roomId);
        if (data == null) return null;
        if (!"ON".equalsIgnoreCase(data.path("realLiveStatus").asText(""))) return null;
        JsonNode list = data.path("stream").path("baseSteamInfoList");
        if (!list.isArray() || list.isEmpty()) return null;
        for (String cdn : new String[]{"TX", "HS", "AL"}) {
            for (JsonNode s : list) {
                if (!cdn.equalsIgnoreCase(s.path("sCdnType").asText(""))) continue;
                String host = s.path("sFlvUrl").asText("");
                String name = s.path("sStreamName").asText("");
                String anti = s.path("sFlvAntiCode").asText("");
                if (!host.isEmpty() && !name.isEmpty() && !anti.isEmpty()) {
                    return host + "/" + name + ".flv?" + anti;
                }
            }
        }
        return null;
    }
}
