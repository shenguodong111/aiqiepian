package com.aiqiepian.engine.real;

import com.aiqiepian.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Inflater;

/**
 * 弹幕增强通道采集器（M2，可选）。
 * 启用条件：settings 三件套 bili_sessdata/bili_jct/bili_uid 齐全，否则整体静默跳过。
 * 链路：getDanmuInfo(带 Cookie) → host_list + token → java.net.http.WebSocket
 *       握手包 op=7（ver=1 JSON，protover=0 要求服务端回 ver=2 zlib）
 *       → 心跳 op=2/30s → op=5 消息按 zlib(Inflater) 解压 → 子包/JSON 解析弹幕 →
 *       落盘 media/{roomId}_{sessionId}_danmaku.jsonl（{"ts","uid","text"} 每行一条）。
 * 设计约束：任何一步失败只记日志（绝不输出 Cookie/token），不抛异常、不影响录制与 ANALYZE job；
 * 分析侧（RealAnalyzer.loadDanmaku）只读该 jsonl，文件不存在则 danmaku_heat=0。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class DanmakuCollector {

    private static final Logger log = LoggerFactory.getLogger(DanmakuCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    /** op=7 握手 / op=8 握手回包 / op=2 心跳 / op=3 心跳回包 / op=5 弹幕广播 / op=6 发送回应 */
    private static final int OP_AUTH = 7;
    private static final int OP_HEARTBEAT = 2;
    private static final int OP_DANMAKU = 5;

    private final SettingsService settings;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static class Session {
        final Path outFile;
        volatile java.net.Socket sock;
        volatile java.io.OutputStream out;
        ScheduledExecutorService heartbeat;
        ScheduledFuture<?> future;

        Session(Path outFile) {
            this.outFile = outFile;
        }
    }

    /** sessionId → 采集会话（内存句柄，录制 stop 时关闭） */
    private final Map<Long, Session> active = new ConcurrentHashMap<>();

    /**
     * 录制开始时挂接：Cookie 齐全则异步起 WS 采集线程；任何条件不满足直接返回（静默降级）。
     * 由 RealRecorder.start 调用，绝不向调用方抛异常。
     */
    public void begin(String biliRoomId, long sessionId) {
        try {
            // 优先使用完整浏览器 Cookie（含已激活的 buvid3/b_nut 指纹，可过 -352 风控）；否则回退登录三件套
            String full = settings.getStr("bili_browser_cookie", "");
            String sessdata = settings.getStr("bili_sessdata", "");
            String jct = settings.getStr("bili_jct", "");
            String uid = settings.getStr("bili_uid", "");
            String cookie;
            if (!full.isBlank()) {
                cookie = full.trim();
            } else if (!sessdata.isBlank() && !jct.isBlank() && !uid.isBlank()) {
                cookie = "SESSDATA=" + sessdata + "; bili_jct=" + jct + "; DedeUserID=" + uid;
            } else {
                log.info("弹幕增强通道未启用：未配置 B站 Cookie（纯音频/画面分析照常）");
                return;
            }
            String uidForWs = uid.isBlank() ? "0" : uid;
            Path dir = Path.of(settings.getStr("media_dir", "./media"));
            Files.createDirectories(dir);
            Path out = dir.resolve(biliRoomId + "_" + sessionId + "_danmaku.jsonl");
            Files.deleteIfExists(out);
            Session s = new Session(out);
            active.put(sessionId, s);
            Thread t = new Thread(() -> run(biliRoomId, uidForWs, cookie, s, sessionId),
                    "danmaku-collect-" + sessionId);
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            log.warn("弹幕采集启动失败（降级，不影响录制）: {}", e.toString());
        }
    }

    /** 录制结束时关闭：停止心跳、关闭 WS。由 RealRecorder.stop 调用，绝不抛异常。 */
    public void end(long sessionId) {
        Session s = active.remove(sessionId);
        if (s == null) return;
        try {
            if (s.future != null) s.future.cancel(true);
            if (s.heartbeat != null) s.heartbeat.shutdownNow();
            if (s.sock != null) s.sock.close();
        } catch (Exception e) {
            log.debug("弹幕采集关闭异常（忽略）: {}", e.toString());
        }
    }

    // ---------- 采集主流程（全部在守护线程内，失败只记日志） ----------

    private void run(String roomId, String uid, String cookie, Session s, long sessionId) {
        try {
            JsonNode info = getDanmuInfo(roomId, cookie);
            if (info == null) {
                log.warn("弹幕通道降级：getDanmuInfo 失败（可能匿名风控或 Cookie 无效）room={}", roomId);
                active.remove(sessionId);
                return;
            }
            String token = info.path("token").asText("");
            JsonNode hosts = info.path("host_list");
            String host = hosts.path(0).path("host").asText("");
            int port = hosts.path(0).path("port").asInt(2243);
            if (host.isEmpty()) {
                log.warn("弹幕通道降级：getDanmuInfo 无 host_list room={}", roomId);
                active.remove(sessionId);
                return;
            }
            java.util.regex.Matcher mb = java.util.regex.Pattern.compile("buvid3=([^;]+)").matcher(cookie);
            String buvid = mb.find() ? mb.group(1) : "";
            Listener rd = new Listener(s, roomId, uid, token, sessionId, buvid);
            // TCP 2243 通道：无 TLS，不受浏览器指纹风控影响（Node 探针实测 ACK）
            try (java.net.Socket sock = new java.net.Socket()) {
                s.sock = sock;
                sock.connect(new java.net.InetSocketAddress(host, port), 8000);
                java.io.OutputStream out = sock.getOutputStream();
                s.out = out;
                out.write(rd.handshakeBytes());
                out.flush();
                s.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r, "danmaku-heartbeat-" + sessionId);
                    th.setDaemon(true);
                    return th;
                });
                s.future = s.heartbeat.scheduleWithFixedDelay(() -> sendHeartbeat(s), 25, 25, TimeUnit.SECONDS);
                log.info("弹幕采集已连接(TCP) room={} session={} file={}", roomId, sessionId, s.outFile);
                java.io.DataInputStream in = new java.io.DataInputStream(sock.getInputStream());
                byte[] header = new byte[16];
                while (active.containsKey(sessionId)) {
                    in.readFully(header);
                    int total = readInt(header, 0);
                    if (total < 16 || total > (8 << 20)) break;
                    byte[] pkt = new byte[total];
                    System.arraycopy(header, 0, pkt, 0, 16);
                    in.readFully(pkt, 16, total - 16);
                    rd.handleMessage(pkt);
                }
            }
            log.info("弹幕采集线程结束 room={} session={}", roomId, sessionId);
        } catch (Exception e) {
            if (active.containsKey(sessionId)) {
                log.warn("弹幕通道降级（不影响录制/分析）: {}", e.toString());
            } else {
                log.debug("弹幕采集随录制正常关闭: {}", e.toString());
            }
        } finally {
            active.remove(sessionId);
            if (s.heartbeat != null) s.heartbeat.shutdownNow();
        }
    }

    private JsonNode getDanmuInfo(String roomId, String cookie) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id="
                                    + roomId + "&type=0"))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UA)
                    .header("Referer", "https://live.bilibili.com/" + roomId)
                    .header("Cookie", cookie)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("getDanmuInfo http {}（弹幕通道降级）", resp.statusCode());
                return null;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            JsonNode data = root.path("data");
            if (code == 0 && data.hasNonNull("host_list")) return data;
            // 只记 code/message，绝不落 Cookie
            log.warn("getDanmuInfo code={}，尝试旧版 getConf 端点", code);
            return getConfFallback(roomId, cookie);
        } catch (Exception e) {
            log.warn("getDanmuInfo 异常，尝试旧版 getConf 端点: {}", e.toString());
            return getConfFallback(roomId, cookie);
        }
    }

    /** 旧版 getConf 风控较松（实测 -352 时可用），响应含 host+token，归一化成 host_list 结构 */
    private JsonNode getConfFallback(String roomId, String cookie) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://api.live.bilibili.com/room/v1/Danmu/getConf?room_id=" + roomId))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UA)
                    .header("Referer", "https://live.bilibili.com/" + roomId)
                    .header("Cookie", cookie)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.path("data");
            if (root.path("code").asInt(-1) != 0 || !data.hasNonNull("host")) {
                log.warn("getConf 也被拦 code={}（弹幕通道降级）", root.path("code").asInt(-1));
                return null;
            }
            var norm = MAPPER.createObjectNode();
            norm.put("token", data.path("token").asText(""));
            var host = norm.putArray("host_list").addObject();
            host.put("host", data.path("host").asText(""));
            host.put("port", data.path("port").asInt(2243));
            log.info("弹幕通道经 getConf 旧端点建立 room={}", roomId);
            return norm;
        } catch (Exception e) {
            log.warn("getConf 异常（弹幕通道降级）: {}", e.toString());
            return null;
        }
    }

    private void sendHeartbeat(Session s) {
        try {
            java.io.OutputStream out = s.out;
            if (s.sock != null && s.sock.isConnected() && !s.sock.isClosed() && out != null) {
                synchronized (s) {
                    out.write(packet(OP_HEARTBEAT, 1, "{}".getBytes(StandardCharsets.UTF_8)));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- TCP 包解析 ----------

    private class Listener {
        private final Session s;
        private final String roomId;
        private final String uid;
        private final String token;
        private final String buvid;
        private final long sessionId;

        Listener(Session s, String roomId, String uid, String token, long sessionId, String buvid) {
            this.s = s;
            this.roomId = roomId;
            this.uid = uid;
            this.token = token;
            this.sessionId = sessionId;
            this.buvid = buvid;
        }

        byte[] handshakeBytes() {
            String auth = String.format(
                    "{\"uid\":%d,\"roomid\":%d,\"protover\":0,\"buvid\":\"%s\",\"key\":\"%s\"}",
                    Long.parseLong(uid), Long.parseLong(roomId), buvid, token);
            return packet(OP_AUTH, 1, auth.getBytes(StandardCharsets.UTF_8));
        }

        private void handleMessage(byte[] msg) throws Exception {
            int offset = 0;
            while (offset + 16 <= msg.length) {
                int total = readInt(msg, offset);
                int headLen = (msg[offset + 4] & 0xFF) << 8 | (msg[offset + 5] & 0xFF);
                int ver = (msg[offset + 6] & 0xFF) << 8 | (msg[offset + 7] & 0xFF);
                int op = readInt(msg, offset + 8);
                if (total <= 0 || offset + total > msg.length) break;
                byte[] payload = java.util.Arrays.copyOfRange(msg, offset + headLen, offset + total);
                offset += total;
                if (op == OP_DANMAKU) {
                    if (ver == 2 || ver == 3) {
                        inflate(payload, ver);
                    } else {
                        scanSubPacketsOrJson(payload);
                    }
                } else if (op == OP_AUTH) {
                    log.debug("弹幕握手回包 op={} size={}", op, payload.length);
                }
            }
        }

        /** op=5 ver=2/3：整段 zlib 解压后可能是[子包流]也可能是[裸 JSON]，两种都兼容 */
        private void inflate(byte[] payload, int ver) throws Exception {
            Inflater inflater = new Inflater();
            inflater.setInput(payload);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            try {
                while (!inflater.finished()) {
                    int n = inflater.inflate(tmp);
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        // ver=3 brotli 场景：inflate 无法完成时直接放弃该包
                        if (ver == 3) return;
                        break;
                    }
                    raw.write(tmp, 0, n);
                }
            } finally {
                inflater.end();
            }
            if (ver == 3) return; // brotli 无依赖库，跳过（protover=0 握手下服务端应回 zlib）
            scanSubPacketsOrJson(raw.toByteArray());
        }

        private void scanSubPacketsOrJson(byte[] data) throws Exception {
            if (data.length == 0) return;
            int head = data[0];
            if (head == '[' || head == '{') { // 裸 JSON 数组
                parseDanmakuJson(new String(data, StandardCharsets.UTF_8));
                return;
            }
            int offset = 0;
            while (offset + 16 <= data.length) {
                int total = readInt(data, offset);
                int headLen = (data[offset + 4] & 0xFF) << 8 | (data[offset + 5] & 0xFF);
                int op = readInt(data, offset + 8);
                if (total < 16 || offset + total > data.length || headLen < 16 || headLen > total) break;
                if (op == 7 || op == 6 || op == 3) { // 子包里的弹幕/通知载荷
                    String json = new String(data, offset + headLen, total - headLen, StandardCharsets.UTF_8);
                    parseDanmakuJson(json);
                }
                offset += total;
            }
        }

        /** 兼容三种形态：单对象 {cmd,info}、对象数组 [{info}]、裸 info 数组 */
        private void parseDanmakuJson(String json) throws Exception {
            JsonNode root = MAPPER.readTree(json);
            if (root.isArray()) {
                for (JsonNode el : root) {
                    parseOne(el.isObject() ? el.path("info") : el);
                }
            } else {
                parseOne(root.path("info"));
            }
        }

        private void parseOne(JsonNode info) throws Exception {
            if (!info.isArray() || info.size() < 5) return;
            String text = info.path(1).asText("");
            if (text.isEmpty()) return;
            long uidNum = info.path(0).path(0).asLong(0);
            long ts = info.path(4).path(0).asLong(System.currentTimeMillis() / 1000);
            appendLine(String.format("{\"ts\":%d,\"uid\":%d,\"text\":%s}",
                    ts, uidNum, MAPPER.writeValueAsString(text)));
        }

        private void appendLine(String line) {
            try {
                synchronized (s.outFile) {
                    Files.writeString(s.outFile, line + System.lineSeparator(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (Exception e) {
                log.debug("弹幕落盘失败（忽略）: {}", e.toString());
            }
        }
    }

    /** 16 字节大端包头：totalLen(4) / headLen(2)=16 / version(2) / operation(4) / seq(4)=1 */
    private static byte[] packet(int op, int ver, byte[] body) {
        int total = 16 + body.length;
        ByteBuffer bb = ByteBuffer.allocate(total);
        bb.putInt(total);
        bb.putShort((short) 16);
        bb.putShort((short) ver);
        bb.putInt(op);
        bb.putInt(1);
        bb.put(body);
        return bb.array();
    }

    private static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16
                | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }
}
