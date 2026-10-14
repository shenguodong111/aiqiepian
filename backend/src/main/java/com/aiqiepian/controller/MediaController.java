package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.Map;

/** §6 #13 素材库 + 媒体文件流式播放 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(@RequestParam(required = false) String category,
                                                             @RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(mediaService.list(category, page, size));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(mediaService.stats());
    }

    /** body {scope}，支持 published_recordings */
    @PostMapping("/cleanup")
    public ApiResponse<Map<String, Object>> cleanup(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(mediaService.cleanup(BodyUtils.str(body, "scope", null)));
    }

    /** 素材库按分类批量删除。body {category, ids:[..], force?:bool=false} */
    @PostMapping("/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        String category = BodyUtils.str(body, "category", null);
        java.util.List<Long> ids = BodyUtils.longList(body, "ids");
        boolean force = BodyUtils.boolVal(body, "force", false);
        return ApiResponse.ok(mediaService.deleteSelected(category, ids, force));
    }

    /** 删除影响预览（确认框用它显示连带删除量）。body {category, ids:[..]} */
    @PostMapping("/delete-impact")
    public ApiResponse<Map<String, Object>> deleteImpact(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(mediaService.deleteImpact(
                BodyUtils.str(body, "category", null), BodyUtils.longList(body, "ids")));
    }

    /**
     * 一键清理冗余。body 可选：{keep_per_session:int=3, delete_files:bool=true, purge_orphans:bool=true}。
     * 每会话只留最高分前 N 片段（多余片段连同作品/记录/文件删除），可清孤儿磁盘文件；已发布内容与房间不动。
     */
    @PostMapping("/cleanup-redundant")
    public ApiResponse<Map<String, Object>> cleanupRedundant(@RequestBody(required = false) Map<String, Object> body) {
        int keep = BodyUtils.intVal(body, "keep_per_session", 3);
        boolean deleteFiles = BodyUtils.boolVal(body, "delete_files", true);
        boolean purgeOrphans = BodyUtils.boolVal(body, "purge_orphans", true);
        return ApiResponse.ok(mediaService.cleanupRedundant(keep, deleteFiles, purgeOrphans));
    }

    /**
     * 流式播放/预览：type=session|work|clip|cover|asset，id=对应记录主键。
     * 路径全部由服务端从 DB 解析（前端无法传任意路径），支持 HTTP Range 以便 <video> 拖动。
     */
    @GetMapping("/stream")
    public void stream(@RequestParam String type,
                       @RequestParam long id,
                       @RequestHeader(value = "Range", required = false) String range,
                       HttpServletResponse resp) throws Exception {
        File f = mediaService.resolveStream(type, id);
        if (f == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
            return;
        }
        long len = f.length();
        resp.reset();
        resp.setContentType(contentType(f.getName()));
        resp.setHeader("Accept-Ranges", "bytes");

        long start = 0;
        long end = len - 1;
        boolean partial = false;
        if (range != null && range.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String sStr = spec.substring(0, dash).trim();
                    String eStr = spec.substring(dash + 1).trim();
                    long s = sStr.isEmpty() ? Math.max(0, len - parseSuffix(eStr)) : Long.parseLong(sStr);
                    long e = eStr.isEmpty() || sStr.isEmpty() ? len - 1 : Long.parseLong(eStr);
                    if (s <= e && s < len) {
                        start = s;
                        end = Math.min(e, len - 1);
                        partial = true;
                    } else if (s >= len) {
                        resp.setStatus(416);
                        resp.setHeader("Content-Range", "bytes */" + len);
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // 无法解析 → 按整文件返回
                }
            }
        }
        long contentLen = end - start + 1;
        if (partial) {
            resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            resp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + len);
        }
        resp.setContentLengthLong(contentLen);

        try (RandomAccessFile raf = new RandomAccessFile(f, "r");
             OutputStream out = resp.getOutputStream()) {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = contentLen;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n <= 0) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        }
    }

    private static long parseSuffix(String eStr) {
        try {
            return Long.parseLong(eStr.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String contentType(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".srt") || n.endsWith(".vtt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
