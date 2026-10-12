package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.service.RecordingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** §6 #5-#8 录制会话 */
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
public class RecordingController {

    private final RecordingService recordingService;

    @GetMapping
    public ApiResponse<PageResult<RecordingSession>> list(@RequestParam(required = false) String status,
                                                          @RequestParam(required = false) Long roomId,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(recordingService.list(status, roomId, page, size));
    }

    /** 手动录制：body {roomId}（mock 用 camelCase，同时兼容 room_id） */
    @PostMapping
    public ApiResponse<RecordingSession> manualRecord(@RequestBody Map<String, Object> body) {
        Long roomId = BodyUtils.longVal(body, "roomId");
        if (roomId == null) roomId = BodyUtils.longVal(body, "room_id");
        return ApiResponse.ok(recordingService.manualRecord(roomId));
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<RecordingSession> stop(@PathVariable long id) {
        return ApiResponse.ok(recordingService.stop(id));
    }

    @PostMapping("/{id}/analyze")
    public ApiResponse<Boolean> analyze(@PathVariable long id) {
        return ApiResponse.ok(recordingService.analyze(id));
    }
}
