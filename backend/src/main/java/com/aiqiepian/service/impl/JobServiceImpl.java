package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.mapper.PipelineJobMapper;
import com.aiqiepian.service.JobService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class JobServiceImpl implements JobService {

    private final PipelineJobMapper jobMapper;

    @Override
    public PageResult<PipelineJob> list(String status, String type, Integer page, Integer size) {
        var q = Wrappers.<PipelineJob>lambdaQuery().orderByDesc(PipelineJob::getId);
        if (status != null && !status.isEmpty()) q.eq(PipelineJob::getStatus, status);
        if (type != null && !type.isEmpty()) q.eq(PipelineJob::getJobType, type);
        List<PipelineJob> list = jobMapper.selectList(q);
        return PageResult.of(list, page, size);
    }

    @Override
    public PipelineJob retry(long id) {
        PipelineJob job = jobMapper.selectById(id);
        if (job == null) throw new BizException(404, "不存在");
        job.setStatus("PENDING");
        job.setProgress(0);
        job.setRetryCount(0);
        jobMapper.updateById(job);
        return job;
    }
}
