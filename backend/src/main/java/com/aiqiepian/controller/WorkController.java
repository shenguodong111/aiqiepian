package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Work;
import com.aiqiepian.service.WorkService;
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

import java.util.List;
import java.util.Map;

/** §6 #10 作品 */
@RestController
@RequestMapping("/api/works")
@RequiredArgsConstructor
public class WorkController {

    private final WorkService workService;

    @GetMapping
    public ApiResponse<PageResult<Work>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(workService.list(status, page, size));
    }

    /** body {clipId,...}（mock 契约为 camelCase clipId） */
    @PostMapping
    public ApiResponse<Work> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workService.createFromClip(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<Work> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workService.update(id, body));
    }

    @PostMapping("/{id}/export")
    public ApiResponse<Work> export(@PathVariable long id) {
        return ApiResponse.ok(workService.export(id));
    }

    @PostMapping("/{id}/submit-review")
    public ApiResponse<Boolean> submitReview(@PathVariable long id) {
        return ApiResponse.ok(workService.submitReview(id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Work> publish(@PathVariable long id) {
        return ApiResponse.ok(workService.publish(id));
    }

    /** FR-P5-05 智能标题建议：{title, kind, summary}，前端填入编辑框后可改名再发布 */
    @PostMapping("/{id}/suggest-title")
    public ApiResponse<Map<String, Object>> suggestTitle(@PathVariable long id) {
        return ApiResponse.ok(workService.suggestTitle(id));
    }

    /** 删除作品（连带文件/审核/发布记录）；已发布需 force=true；delete_files=false 仅删记录不删盘 */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id,
                                                   @RequestParam(defaultValue = "false") boolean force,
                                                   @RequestParam(name = "delete_files", defaultValue = "true") boolean deleteFiles) {
        return ApiResponse.ok(workService.delete(id, force, deleteFiles));
    }

    /** 批量删除 body {ids:[..], force?:bool, delete_files?:bool=true} */
    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body) {
        List<Long> ids = BodyUtils.longList(body, "ids");
        boolean force = BodyUtils.boolVal(body, "force", false);
        boolean deleteFiles = BodyUtils.boolVal(body, "delete_files", true);
        return ApiResponse.ok(workService.batchDelete(ids, force, deleteFiles));
    }
}
