package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.ReviewRecord;
import com.aiqiepian.entity.Work;

import java.util.Map;

public interface ReviewService {

    /** 待人工处理：status=READY 且存在 MANUAL 审核记录的作品 */
    PageResult<Work> pending(Integer page, Integer size);

    /** 人工决策 pass/reject；拒绝必填原因；pass 且开启自动发布并已绑定账号时自动入发布队列 */
    Work decision(long workId, String decision, String reason);

    PageResult<ReviewRecord> records(Integer page, Integer size);

    /** { auto_pass_score, auto_reject_score, auto_work_min_score } */
    Map<String, Object> rules();

    Map<String, Object> updateRules(Map<String, Object> patch);
}
