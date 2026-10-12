package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.PipelineJob;

public interface JobService {

    PageResult<PipelineJob> list(String status, String type, Integer page, Integer size);

    /** 手动重试：FAILED/任意态 → PENDING、progress 归零、retry_count 清零 */
    PipelineJob retry(long id);
}
