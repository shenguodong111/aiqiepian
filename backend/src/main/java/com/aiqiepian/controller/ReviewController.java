package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.ReviewRecord;
import com.aiqiepian.entity.Work;
import com.aiqiepian.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** §6 #11 审核 */
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/pending")
    public ApiResponse<PageResult<Work>> pending(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(reviewService.pending(page, size));
    }

    /** body {decision:'pass'|'reject', reason}；拒绝必填原因 */
    @PostMapping("/{workId}/decision")
    public ApiResponse<Work> decision(@PathVariable long workId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reviewService.decision(workId,
                BodyUtils.str(body, "decision", null),
                BodyUtils.str(body, "reason", null)));
    }

    @GetMapping("/records")
    public ApiResponse<PageResult<ReviewRecord>> records(@RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(reviewService.records(page, size));
    }

    @GetMapping("/rules")
    public ApiResponse<Map<String, Object>> rules() {
        return ApiResponse.ok(reviewService.rules());
    }

    @PutMapping("/rules")
    public ApiResponse<Map<String, Object>> updateRules(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(reviewService.updateRules(body));
    }
}
