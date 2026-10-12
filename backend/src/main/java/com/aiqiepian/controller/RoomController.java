package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.service.RoomService;
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

import java.util.Map;

/** §6 #2-#4 直播间 CRUD / 启停 / 立即检查 */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @GetMapping
    public ApiResponse<PageResult<LiveRoom>> list(@RequestParam(required = false) String platform,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String kw,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(roomService.list(platform, status, kw, page, size));
    }

    @PostMapping
    public ApiResponse<LiveRoom> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(roomService.create(body));
    }

    @PutMapping("/{id}")
    public ApiResponse<LiveRoom> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(roomService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable long id) {
        roomService.delete(id);
        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<LiveRoom> toggle(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(roomService.toggle(id, BodyUtils.truthy(body.get("enabled"))));
    }

    @PostMapping("/{id}/check")
    public ApiResponse<Map<String, Object>> check(@PathVariable long id) {
        return ApiResponse.ok(roomService.check(id));
    }
}
