package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.Compilation;
import com.aiqiepian.service.CompilationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** M4-2 合集：列表 / 新建 / 生成（ffmpeg concat）/ 删除 */
@RestController
@RequestMapping("/api/compilations")
@RequiredArgsConstructor
public class CompilationController {

    private final CompilationService compilationService;

    @GetMapping
    public ApiResponse<PageResult<Compilation>> list(@RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(compilationService.list(page, size));
    }

    /** body: {title, work_ids:[...]}（兼容 workIds / 逗号字符串） */
    @PostMapping
    public ApiResponse<Compilation> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(compilationService.create(body));
    }

    /** 立即返回（状态 RENDERING），后台线程 concat 渲染 */
    @PostMapping("/{id}/render")
    public ApiResponse<Compilation> render(@PathVariable long id) {
        return ApiResponse.ok(compilationService.render(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable long id) {
        compilationService.delete(id);
        return ApiResponse.ok(true);
    }
}
