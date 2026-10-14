package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.engine.model.PublishAccount;

import java.util.Map;

public interface PublishService {

    /** 未绑定时返回 null（响应 data 为 null，与 mock 一致） */
    PublishAccount getAccount();

    PublishAccount bind(String nickname);

    void unbind();

    /** M3 §5：real 模式下用 Cookie 调 nav() 校验账号；mock 模式返回 login=false 提示 */
    Map<String, Object> checkAccount();

    /** 扫码登录第一步：申请二维码（返回 qrcode_key + PNG data URL）；非 real 模式返回 ok=false */
    Map<String, Object> qrcodeStart();

    /** 扫码登录第二步：轮询该 key；扫码确认后自动写入 Cookie 并绑定账号 */
    Map<String, Object> qrcodePoll(String qrcodeKey);

    /** 用现有 Cookie 调 B站续期接口，能续则回写轮换后的 Cookie */
    Map<String, Object> refreshCookie();

    /** Cookie 体检状态：{status: VALID|EXPIRED|MISSING|UNKNOWN|MOCK, checked_at, message, nickname} */
    Map<String, Object> cookieStatus();

    PageResult<PublishTask> tasks(String status, Integer page, Integer size);

    /** 重试失败任务：QUEUED / phase 归零 */
    PublishTask retry(long id);

    /** 已发布记录 */
    PageResult<PublishTask> records(Integer page, Integer size);
}
