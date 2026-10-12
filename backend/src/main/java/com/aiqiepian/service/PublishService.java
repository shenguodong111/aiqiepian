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

    PageResult<PublishTask> tasks(String status, Integer page, Integer size);

    /** 重试失败任务：QUEUED / phase 归零 */
    PublishTask retry(long id);

    /** 已发布记录 */
    PageResult<PublishTask> records(Integer page, Integer size);
}
