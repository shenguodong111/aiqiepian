package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.entity.ReviewRecord;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ReviewRecordMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.PipelineService;
import com.aiqiepian.service.PublishService;
import com.aiqiepian.service.ReviewService;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRecordMapper reviewMapper;
    private final WorkMapper workMapper;
    private final SettingsService settings;
    private final PublishService publishService;
    private final PipelineService pipeline;

    @Override
    public PageResult<Work> pending(Integer page, Integer size) {
        Set<Long> manualIds = reviewMapper.selectList(Wrappers.<ReviewRecord>lambdaQuery()
                        .eq(ReviewRecord::getDecision, "MANUAL"))
                .stream().map(ReviewRecord::getWorkId).collect(Collectors.toSet());
        List<Work> list = workMapper.selectList(Wrappers.<Work>lambdaQuery()
                        .eq(Work::getStatus, "READY").orderByDesc(Work::getId))
                .stream().filter(w -> manualIds.contains(w.getId())).toList();
        return PageResult.of(list, page, size);
    }

    @Override
    public Work decision(long workId, String decision, String reason) {
        Work w = workMapper.selectById(workId);
        if (w == null) throw new BizException(404, "不存在");
        boolean pass = "pass".equals(decision);
        if (!pass && (reason == null || reason.isBlank())) throw new BizException("拒绝必须填写原因");
        w.setStatus(pass ? "APPROVED" : "REJECTED");
        w.setUpdatedAt(LocalDateTime.now());
        workMapper.updateById(w);

        ReviewRecord r = new ReviewRecord();
        r.setWorkId(w.getId());
        r.setDecision(pass ? "MANUAL_PASS" : "MANUAL_REJECT");
        r.setConfidence(100);
        r.setReason(pass && (reason == null || reason.isBlank()) ? "人工放行" : reason);
        r.setOperator("local");
        r.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(r);

        if (pass && settings.getBool("auto_publish", true)) {
            PublishAccount account = publishService.getAccount();
            if (account != null) pipeline.queuePublish(w);
        }
        return w;
    }

    @Override
    public PageResult<ReviewRecord> records(Integer page, Integer size) {
        List<ReviewRecord> list = reviewMapper.selectList(
                Wrappers.<ReviewRecord>lambdaQuery().orderByDesc(ReviewRecord::getId));
        return PageResult.of(list, page, size);
    }

    @Override
    public Map<String, Object> rules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auto_pass_score", settings.getInt("auto_pass_score", 85));
        m.put("auto_reject_score", settings.getInt("auto_reject_score", 70));
        m.put("auto_work_min_score", settings.getInt("auto_work_min_score", 70));
        return m;
    }

    @Override
    public Map<String, Object> updateRules(Map<String, Object> patch) {
        if (patch != null) {
            patch.forEach(settings::setValue);
        }
        return rules();
    }
}
