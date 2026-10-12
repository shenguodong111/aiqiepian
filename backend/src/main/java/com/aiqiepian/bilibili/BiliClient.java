package com.aiqiepian.bilibili;

import com.aiqiepian.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Bilibili 创作中心 Web 协议客户端（M3 真实上传发布引擎，Cookie 方案，无 OAuth）。
 *
 * <p>认证方式与 biliup 等第三方工具一致：用户自备 SESSDATA / bili_jct / DedeUserID 三件套，
 * 存于 app_setting（bili_sessdata / bili_jct / bili_uid），对外接口一律掩码。
 *
 * <p>安全约束：本类任何日志都不输出 Cookie 原文，只输出 {@link #mask(String)} 后的脱敏值。
 */
@Component
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class BiliClient {

    private static final Logger log = LoggerFactory.getLogger(BiliClient.class);

    public static final String NAV_URL = "https://member.bilibili.com/x/web-interface/nav";
    /** member nav 在部分网络返回 404 页面（非 JSON），回退同结构端点 */
    public static final String NAV_FALLBACK_URL = "https://api.bilibili.com/x/web-interface/nav";
    /**
     * upos/bup 上传协议入口（2026-10 实测可用；x/space/pc/video/upload 与 x/media/pc/v2/upload 均已 404 废弃）。
     * 流程：GET preupload → POST {cdn}/{path}?uploads → PUT 分片 → POST complete(parts) →
     * 稿件提交 POST member.bilibili.com/x/vu/web/add?csrf=。
     */
    public static final String PREUPLOAD_URL =
            "https://member.bilibili.com/preupload?probe_version=20221109&upcdn=bda2&zone=cs";
    public static final String WEB_ADD_URL = "https://member.bilibili.com/x/vu/web/add";
    public static final String VIEW_URL = "https://api.bilibili.com/x/web-interface/view";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String REFERER = "https://member.bilibili.com/";

    /** PC 创作中心分片大小：10MB */
    public static final long CHUNK_SIZE = 10L * 1024 * 1024;

    private final SettingsService settings;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public BiliClient(SettingsService settings) {
        this.settings = settings;
    }

    // ---------- Cookie ----------

    public String sessdata() {
        return settings.getStr("bili_sessdata", "");
    }

    public String jct() {
        return settings.getStr("bili_jct", "");
    }

    public String uid() {
        return settings.getStr("bili_uid", "");
    }

    /** Cookie 三件套是否齐全（空串/空白视为缺失） */
    public boolean cookiesComplete() {
        return !sessdata().isBlank() && !jct().isBlank() && !uid().isBlank();
    }

    /** 敏感值脱敏：只保留前后 4 位，供任何日志/回显使用 */
    public static String mask(String v) {
        if (v == null || v.isEmpty()) return "";
        if (v.length() <= 8) return "********";
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    private String cookieHeader() {
        return "SESSDATA=" + sessdata() + "; bili_jct=" + jct() + "; DedeUserID=" + uid();
    }

    private HttpHeaders authHeaders(MediaType contentType) {
        HttpHeaders h = new HttpHeaders();
        if (contentType != null) h.setContentType(contentType);
        h.set(HttpHeaders.USER_AGENT, UA);
        h.set(HttpHeaders.REFERER, REFERER);
        h.set(HttpHeaders.COOKIE, cookieHeader());
        return h;
    }

    private JsonNode getJson(String url, HttpHeaders headers) {
        try {
            ResponseEntity<String> resp = rest.exchange(java.net.URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return mapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
        } catch (RestClientException | java.io.IOException e) {
            throw new BiliApiException(-1, "网络请求失败(" + url + "): " + truncate(e.getMessage()));
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...(截断)";
    }

    // ---------- 1. nav 校验 ----------

    /** GET member nav：code==0 且 data.isLogin==true 视为 Cookie 有效 */
    public NavInfo nav() {
        JsonNode json;
        try {
            json = getJson(NAV_URL, authHeaders(null));
            if (!json.has("code")) {
                throw new BiliApiException(-1, "nav 响应缺少 code 字段");
            }
        } catch (RuntimeException e) {
            // member.bilibili.com 在部分网络环境返回 404 错误页（非 JSON）→ 回退 api.bilibili.com 同结构端点
            log.info("member nav 不可用（{}），回退 api.bilibili.com nav", truncate(e.getMessage()));
            json = getJson(NAV_FALLBACK_URL, authHeaders(null));
        }
        int code = json.path("code").asInt(-999);
        String message = json.path("message").asText("");
        JsonNode data = json.path("data");
        boolean isLogin = data.path("isLogin").asBoolean(false);
        if (code == 0 && isLogin) {
            Long mid = data.path("mid").isMissingNode() || data.path("mid").isNull()
                    ? null : data.path("mid").asLong();
            String uname = data.path("uname").asText(null);
            log.info("bili nav 校验通过: uname={} mid={}", uname, mid);
            return new NavInfo(code, message, true, uname, mid);
        }
        // -101 → 未登录/Cookie 无效；其余 code 原样回传，不吞错
        log.info("bili nav 校验未通过: code={} message={}", code, message);
        return NavInfo.notLoggedIn(code, message);
    }

    // ---------- 2. upos 分片上传（biliup 同源协议） ----------

    /**
     * upos 上传：preupload 取桶信息 → ?uploads 申请 upload_id → 逐片 PUT（10MB 分片，进度回调 0-100）
     * → complete 提交 parts 拿 location。任一阶段非 0/非 OK 抛 {@link BiliApiException}（原始错误不吞）。
     */
    public UploadOutcome uploadVideo(File file, String videoName, long mid, IntConsumer percent) {
        long size = file.length();
        // 1) preupload
        String preUrl = PREUPLOAD_URL + "&r=upos&profile=ugcupos%2Fbup&ssl=0&version=2.11.0&build=2110000"
                + "&name=" + urlEncode(videoName) + "&size=" + size;
        JsonNode pre = parse(getText(preUrl, authHeaders(null)));
        if (pre.path("OK").asInt(-1) != 1) {
            throw new BiliApiException(-4, "preupload 未就绪: " + preview(pre.toString()));
        }
        String auth = pre.path("auth").asText();
        String endpoint = pre.path("endpoint").asText();          // 形如 //upos-cs-upcdnbda2.bilivideo.com
        String uposUri = pre.path("upos_uri").asText();           // upos://bucket/xxxxxxxx.mp4
        long bizId = pre.path("biz_id").asLong();
        long chunkSize = pre.path("chunk_size").asLong(CHUNK_SIZE);
        String url = "https:" + endpoint + "/" + uposUri.replace("upos://", "");
        log.info("bili preupload ok endpoint={} chunk={} size={}", endpoint, chunkSize, size);

        // 2) 申请 upload_id
        JsonNode init = parse(postText(url + "?uploads&output=json", "", uposHeaders(auth, null, false)));
        String uploadId = init.path("upload_id").asText(null);
        if (uploadId == null || uploadId.isBlank()) {
            throw new BiliApiException(-5, "upos 初始化上传失败: " + preview(init.toString()));
        }

        // 3) 逐片 PUT（协议：不带 cookie；分片失败重试，规避 CDN「上传过快」/偶发 403/5xx）
        int total = (int) Math.max(1, (size + chunkSize - 1) / chunkSize);
        java.util.List<Map<String, Object>> parts = new java.util.ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            for (int idx = 0; idx < total; idx++) {
                long off = idx * chunkSize;
                int len = (int) Math.min(chunkSize, size - off);
                byte[] chunk = new byte[len];
                raf.seek(off);
                raf.readFully(chunk);
                String putUrl = url + "?upload_id=" + uploadId + "&chunks=" + total + "&total=" + size
                        + "&chunk=" + idx + "&size=" + len + "&part_number=" + (idx + 1)
                        + "&start=" + off + "&end=" + (off + len);
                putChunkWithRetry(putUrl, chunk, uposHeaders(auth, MediaType.APPLICATION_OCTET_STREAM, false), idx + 1, total);
                parts.add(Map.of("partNumber", idx + 1, "eTag", "etag"));
                percent.accept((idx + 1) * 100 / total);
                log.info("bili upos 分片 {}/{} 完成 ({}B)", idx + 1, total, len);
            }
        } catch (BiliApiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new BiliApiException(-2, "读取成片失败: " + e.getMessage());
        }

        // 4) complete（合并分片，拿 location）
        String cq = url + "?name=" + urlEncode(videoName) + "&uploadId=" + uploadId
                + "&biz_id=" + bizId + "&output=json&profile=ugcupos%2Fbup";
        JsonNode done;
        try {
            done = parse(postText(cq, mapper.writeValueAsString(Map.of("parts", parts)),
                    uposHeaders(auth, MediaType.APPLICATION_JSON, true)));
        } catch (java.io.IOException e) {
            throw new BiliApiException(-7, "complete 请求构造失败: " + e.getMessage());
        }
        if (done.path("OK").asInt(0) != 1) {
            throw new BiliApiException(-8, "upos 合并失败: " + preview(done.toString()));
        }
        String location = done.path("location").asText(uposUri.replace("upos://", ""));
        // 稿件 videos[].filename = upos 对象文件名去扩展名（biliup 同源实现，带扩展名会被判 21015）
        String objectName = location.substring(location.lastIndexOf('/') + 1);
        int dot = objectName.lastIndexOf('.');
        if (dot > 0) objectName = objectName.substring(0, dot);
        log.info("bili upos 上传完成 location={} objectName={} 分片数={}", location, objectName, total);
        return new UploadOutcome(location, null, objectName);
    }

    // ---------- 3. 稿件提交（member x/vu/web/add，biliup web 通道同源） ----------

    /**
     * POST /x/vu/web/add?csrf={bili_jct} 提交稿件，成功返回 data.bvid。
     * 实测（2026-10）：api/member 的 /x/web/archives 已 404/405 废弃，本接口仍可用。
     * 转载稿件（copyright=2）必须带 source；可见性按需求固定"仅自己可见"（visibility=private + visible=1，
     * 若平台不识别该字段，接口不会报错——最终以后台复核，见报告遗留风险）。
     */
    public String submitArchive(String title, String tags, String desc, String cover,
                                UploadOutcome upload, long mid) {
        if (upload == null || upload.objectName() == null || upload.objectName().isBlank()) {
            throw new BiliApiException(-9, "缺少已上传视频资源，无法提交稿件");
        }
        String safeTitle = title == null ? "AI切片作品" : title;
        if (safeTitle.length() > 80) safeTitle = safeTitle.substring(0, 80);
        String safeDesc = desc == null ? "" : desc;
        String source = settings.getStr("publish_repost_source",
                "https://live.bilibili.com/ （直播录制切片，个人备份）");

        String coverUrl = cover == null ? "" : cover.trim();
        if (coverUrl.startsWith("http:")) coverUrl = coverUrl.substring(5); // biliup 同款去 http: 前缀

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mid", mid);
        body.put("copyright", 2); // 转载（M3 规格：直播切片属转载，source 必填）
        body.put("source", source);
        body.put("tid", settings.getInt("publish_tid", 234)); // web/add 用单值 int，非数组
        body.put("title", safeTitle);
        body.put("cover", coverUrl);
        body.put("desc", safeDesc);
        body.put("desc_format_id", 0);
        body.put("desc_v2", List.of(Map.of("raw_text", safeDesc, "biz_id", "", "type", 1)));
        body.put("dynamic", safeTitle);
        body.put("subtitle", Map.of("open", 0, "lan", ""));
        body.put("tag", tags == null || tags.isBlank() ? settings.getStr("publish_default_tags", "直播切片,AI剪辑") : tags);
        body.put("videos", List.of(Map.of(
                "filename", upload.objectName(),
                "title", safeTitle.length() > 80 ? safeTitle.substring(0, 80) : safeTitle,
                "desc", "")));
        body.put("dtime", null); // 立即提交（进平台审核），不定时发布
        // 仅自己可见：is_only_self=1（edit 实测生效字段）+ visible/visibility 一并携带，add 阶段若识别则更好
        body.put("is_only_self", 1);
        body.put("visible", 1);
        body.put("visibility", "private");

        String url = WEB_ADD_URL + "?csrf=" + jct();
        String resp;
        try {
            resp = postText(url, mapper.writeValueAsString(body),
                    authHeaders(MediaType.APPLICATION_JSON));
        } catch (RestClientException | java.io.IOException e) {
            throw new BiliApiException(-1, "网络请求失败(" + WEB_ADD_URL + "): " + truncate(e.getMessage()));
        }
        JsonNode json = parse(resp);
        int code = json.path("code").asInt(-999);
        if (code != 0) {
            String msg = json.path("message").asText("");
            log.warn("bili web/add 提交被拒绝: code={} message={}", code, msg);
            throw new BiliApiException(code, msg);
        }
        String bvid = json.path("data").path("bvid").asText(null);
        if (bvid == null || bvid.isBlank()) {
            throw new BiliApiException(code, "稿件提交成功但响应缺少 data.bvid");
        }
        log.info("bili web/add 提交成功: bvid={} aid={}", bvid, json.path("data").path("aid").asText());

        // 私密保险（2026-10-10 实测）：/x/vu/web/add 会静默忽略 visible/visibility 字段（稿件按公开过审），
        // 但 /x/vu/web/edit 认 is_only_self=1 → state=-50「仅自己可见」。提交后立即 edit 一次强制私密。
        // 失败不阻断主流程（稿件已提交，状态回读仍走原逻辑），仅告警。
        long aid = json.path("data").path("aid").asLong(0);
        try {
            body.put("aid", aid);
            body.put("bvid", bvid);
            body.put("is_only_self", 1);
            String editUrl = "https://member.bilibili.com/x/vu/web/edit?csrf=" + jct();
            JsonNode editRet = parse(postText(editUrl, mapper.writeValueAsString(body),
                    authHeaders(MediaType.APPLICATION_JSON)));
            int ec = editRet.path("code").asInt(-999);
            if (ec == 0) {
                log.info("bili web/edit 私密化成功: bvid={}（state 应为 -50 仅自己可见）", bvid);
            } else {
                log.warn("bili web/edit 私密化未生效: bvid={} code={} message={}（稿件可能保持公开，请人工确认）",
                        bvid, ec, editRet.path("message").asText(""));
            }
        } catch (RestClientException | java.io.IOException e) {
            log.warn("bili web/edit 私密化请求异常（不影响已提交稿件）: {}", truncate(e.getMessage()));
        }
        return bvid;
    }

    // ---------- 3b. 状态回读 ----------

    /** GET web-interface/view?bvid=：code==0 且 data 有 bvid 视为转码完成（匿名可见） */
    public JsonNode view(String bvid) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.USER_AGENT, UA);
        return getJson(VIEW_URL + "?bvid=" + bvid, h);
    }

    /** 带 Cookie 的 owner 回读：私密（仅自己可见）稿件匿名 view 永远 -404，只有 owner 可见 code 0 */
    public JsonNode ownerView(String bvid) {
        return getJson(VIEW_URL + "?bvid=" + bvid, authHeaders(null));
    }

    public boolean viewReady(String bvid) {
        JsonNode json = view(bvid);
        int code = json.path("code").asInt(-999);
        boolean ready = code == 0 && json.path("data").hasNonNull("bvid");
        if (!ready) {
            // 私密稿件走 owner 回读
            try {
                JsonNode owner = ownerView(bvid);
                ready = owner.path("code").asInt(-999) == 0 && owner.path("data").hasNonNull("bvid");
                if (ready) log.info("bili owner view 回读通过（仅自己可见稿件）: bvid={}", bvid);
            } catch (RuntimeException ignore) {
                // owner 回读异常不掩盖匿名结果
            }
        }
        if (!ready) log.info("bili view 回读: bvid={} code={} message={}（转码中/暂不可见）",
                bvid, code, json.path("message").asText(""));
        return ready;
    }

    // ---------- 内部 ----------

    private String getText(String url, HttpHeaders headers) {
        try {
            ResponseEntity<String> resp = rest.exchange(java.net.URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return resp.getBody();
        } catch (RestClientException e) {
            throw new BiliApiException(-1, "网络请求失败(" + url + "): " + truncate(e.getMessage()));
        }
    }

    private String postText(String url, String jsonBody, HttpHeaders headers) {
        try {
            ResponseEntity<String> resp = rest.exchange(java.net.URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(jsonBody, headers), String.class);
            return resp.getBody();
        } catch (RestClientException e) {
            throw new BiliApiException(-1, "网络请求失败(" + url + "): " + truncate(e.getMessage()));
        }
    }

    /**
     * 分片 PUT：成功返回响应体（PUT_OK / MULTIPART_PUT_SUCCESS）。
     * 失败（4xx/5xx/网络）最多重试 3 次，每次退避递增（规避 CDN「上传次数过快」偶发 403/5xx）；
     * 全部失败抛 {@link BiliApiException}(-6)，消息带真实 HTTP 状态与响应片段，便于定位。
     */
    private void putChunkWithRetry(String url, byte[] chunk, HttpHeaders headers, int partNo, int total) {
        int attempts = 3;
        String lastErr = "未知错误";
        for (int a = 1; a <= attempts; a++) {
            try {
                ResponseEntity<String> resp = rest.exchange(java.net.URI.create(url), HttpMethod.PUT,
                        new HttpEntity<>(chunk, headers), String.class);
                if (resp.getStatusCode().is2xxSuccessful()) return;
                lastErr = "HTTP " + resp.getStatusCode().value();
            } catch (RestClientResponseException e) {
                String body = e.getResponseBodyAsString();
                lastErr = "HTTP " + e.getStatusCode().value() + " " + e.getStatusText()
                        + (body == null || body.isBlank() ? "" : " · " + truncate(body.trim()));
            } catch (RestClientException e) {
                lastErr = "网络异常: " + truncate(String.valueOf(e.getMessage()));
            }
            log.warn("bili upos 分片 {}/{} 第 {}/{} 次 PUT 失败: {}", partNo, total, a, attempts, lastErr);
            if (a < attempts) {
                try {
                    Thread.sleep(1200L * a);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new BiliApiException(-6, "upos 分片 " + partNo + "/" + total + " PUT 失败: " + lastErr);
    }

    private HttpHeaders uposHeaders(String auth, MediaType contentType, boolean withCookie) {
        HttpHeaders h = withCookie ? authHeaders(contentType) : bareHeaders(contentType);
        h.set("X-Upos-Auth", auth);
        return h;
    }

    /** upos CDN 头：只带 UA/Referer/Content-Type，不带 Cookie（协议规定分片 PUT 不携带 cookie，带反而可能被 CDN 判 403） */
    private HttpHeaders bareHeaders(MediaType contentType) {
        HttpHeaders h = new HttpHeaders();
        if (contentType != null) h.setContentType(contentType);
        h.set(HttpHeaders.USER_AGENT, UA);
        h.set(HttpHeaders.REFERER, REFERER);
        return h;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...(截断)";
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (java.io.IOException e) {
            String preview = body == null ? "" : body.substring(0, Math.min(120, body.length()));
            throw new BiliApiException(-3, "响应不是合法 JSON: " + preview);
        }
    }
}
