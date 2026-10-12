package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** §6 #14 设置（整包 KV）与演示数据重置 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(settingsService.getAll());
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(settingsService.updateAll(body));
    }

    @PostMapping("/reset-demo")
    public ApiResponse<Boolean> resetDemo() {
        settingsService.resetDemoData();
        return ApiResponse.ok(true);
    }
}
