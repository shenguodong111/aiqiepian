package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.service.ConfigSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 跨机迁移：配置导出 / 导入（不含 B 站 Cookie 等凭据） */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigSyncController {

    private final ConfigSyncService configSync;

    @GetMapping("/export")
    public ApiResponse<Map<String, Object>> export(@RequestParam(required = false) String include_history) {
        return ApiResponse.ok(configSync.exportConfig(BodyUtils.truthy(include_history)));
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importConfig(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(configSync.importConfig(body));
    }
}
