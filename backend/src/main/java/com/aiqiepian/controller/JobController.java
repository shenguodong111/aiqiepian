package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §6 #15 任务队列 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @GetMapping
    public ApiResponse<PageResult<PipelineJob>> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String type,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(jobService.list(status, type, page, size));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<PipelineJob> retry(@PathVariable long id) {
        return ApiResponse.ok(jobService.retry(id));
    }
}
