package com.aiqiepian.controller;

import com.aiqiepian.common.ApiResponse;
import com.aiqiepian.common.BodyUtils;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.service.PublishService;
import com.aiqiepian.service.ViewCountService;
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

/** §6 #12 发布 */
@RestController
@RequestMapping("/api/publish")
@RequiredArgsConstructor
public class PublishController {

    private final PublishService publishService;
    private final ViewCountService viewCountService;

    /** 未绑定时 data 为 null */
    @GetMapping("/account")
    public ApiResponse<PublishAccount> account() {
        return ApiResponse.ok(publishService.getAccount());
    }

    /** M3 §5：real 模式"校验Cookie" → {login, uname, mid, code, message} */
    @GetMapping("/account/check")
    public ApiResponse<Map<String, Object>> checkAccount() {
        return ApiResponse.ok(publishService.checkAccount());
    }

    /** 模拟 OAuth：body {nickname}，缺省 "AI切片演示号" */
    @PostMapping("/account/bind")
    public ApiResponse<PublishAccount> bind(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(publishService.bind(BodyUtils.str(body, "nickname", null)));
    }

    /** 扫码登录第一步：取二维码（image 为 PNG data URL，界面直接显示） */
    @PostMapping("/account/qrcode")
    public ApiResponse<Map<String, Object>> qrcodeStart() {
        return ApiResponse.ok(publishService.qrcodeStart());
    }

    /** 扫码登录第二步：轮询；status=confirmed 时后端已自动写入 Cookie 并绑定账号 */
    @GetMapping("/account/qrcode/poll")
    public ApiResponse<Map<String, Object>> qrcodePoll(@RequestParam String key) {
        return ApiResponse.ok(publishService.qrcodePoll(key));
    }

    /** 用现有 Cookie 尝试续期（免重新登录） */
    @PostMapping("/account/refresh")
    public ApiResponse<Map<String, Object>> refreshCookie() {
        return ApiResponse.ok(publishService.refreshCookie());
    }

    /** Cookie 体检状态（设置页徽标 / 发布中心红条用） */
    @GetMapping("/account/status")
    public ApiResponse<Map<String, Object>> cookieStatus() {
        return ApiResponse.ok(publishService.cookieStatus());
    }

    @DeleteMapping("/account")
    public ApiResponse<Boolean> unbind() {
        publishService.unbind();
        return ApiResponse.ok(true);
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<PublishTask>> tasks(@RequestParam(required = false) String status,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(publishService.tasks(status, page, size));
    }

    @PostMapping("/tasks/{id}/retry")
    public ApiResponse<PublishTask> retry(@PathVariable long id) {
        return ApiResponse.ok(publishService.retry(id));
    }

    @GetMapping("/records")
    public ApiResponse<PageResult<PublishTask>> records(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(publishService.records(page, size));
    }

    /** M4-3：手动触发一轮播放量同步（自动版每 10 分钟），返回 {synced, failed} */
    @PostMapping("/views/sync")
    public ApiResponse<Map<String, Object>> syncViews() {
        return ApiResponse.ok(viewCountService.syncAll());
    }
}
