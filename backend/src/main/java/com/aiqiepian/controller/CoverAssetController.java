package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.entity.CoverAsset;
import com.aiqiepian.service.CoverAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 封面素材库：每个主播（房间）的图片底图，成片封面 = 素材图 + 标题文字 */
@RestController
@RequestMapping("/api/cover-assets")
@RequiredArgsConstructor
public class CoverAssetController {

    private final CoverAssetService coverAssetService;

    @GetMapping
    public ApiResponse<List<CoverAsset>> list(@RequestParam(name = "room_id") Long roomId) {
        return ApiResponse.ok(coverAssetService.list(roomId));
    }

    @PostMapping
    public ApiResponse<CoverAsset> upload(@RequestParam(name = "room_id") Long roomId,
                                          @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(coverAssetService.upload(roomId, file));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        return ApiResponse.ok(coverAssetService.delete(id));
    }
}
