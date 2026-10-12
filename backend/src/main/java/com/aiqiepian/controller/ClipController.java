package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.Work;
import com.aiqiepian.service.ClipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** §6 #9 片段 */
@RestController
@RequestMapping("/api/clips")
@RequiredArgsConstructor
public class ClipController {

    private final ClipService clipService;

    @GetMapping
    public ApiResponse<PageResult<Clip>> list(@RequestParam(required = false) Long sessionId,
                                              @RequestParam(required = false) Integer minScore,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(clipService.list(sessionId, minScore, status, page, size));
    }

    /** 编辑时间范围 body {start_sec, end_sec} */
    @PutMapping("/{id}")
    public ApiResponse<Clip> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(clipService.updateRange(id,
                BodyUtils.intVal(body, "start_sec", null),
                BodyUtils.intVal(body, "end_sec", null)));
    }

    @PostMapping("/{id}/discard")
    public ApiResponse<Clip> discard(@PathVariable long id) {
        return ApiResponse.ok(clipService.discard(id));
    }

    /** 恢复已丢弃片段（FR-P4-03） */
    @PostMapping("/{id}/restore")
    public ApiResponse<Clip> restore(@PathVariable long id) {
        return ApiResponse.ok(clipService.restore(id));
    }

    @PostMapping("/{id}/make-work")
    public ApiResponse<Work> makeWork(@PathVariable long id) {
        return ApiResponse.ok(clipService.makeWork(id));
    }

    /** 批量成片 body {ids:[..]} */
    @PostMapping("/batch-make-work")
    public ApiResponse<List<Work>> batchMakeWork(@RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Number n) ids.add(n.longValue());
                else if (o != null) {
                    try { ids.add(Long.parseLong(String.valueOf(o))); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return ApiResponse.ok(clipService.batchMakeWork(ids));
    }

    /** 删除片段（级联删其派生作品+文件）；含已发布作品需 force=true */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id,
                                                   @RequestParam(defaultValue = "false") boolean force,
                                                   @RequestParam(name = "delete_files", defaultValue = "true") boolean deleteFiles) {
        return ApiResponse.ok(clipService.delete(id, force, deleteFiles));
    }

    /** 批量删除 body {ids:[..], force?:bool, delete_files?:bool=true} */
    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body) {
        List<Long> ids = BodyUtils.longList(body, "ids");
        boolean force = BodyUtils.boolVal(body, "force", false);
        boolean deleteFiles = BodyUtils.boolVal(body, "delete_files", true);
        return ApiResponse.ok(clipService.batchDelete(ids, force, deleteFiles));
    }
}
