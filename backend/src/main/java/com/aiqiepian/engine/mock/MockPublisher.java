package com.aiqiepian.engine.mock;

import com.aiqiepian.engine.Publisher;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.engine.model.PublishResult;
import com.aiqiepian.entity.Work;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.aiqiepian.engine.mock.MockSupport.chance;

/** 模拟发布：90% 成功；失败原因与 server.mjs 一致。阶段推进（QUEUED→UPLOADING→PLATFORM_REVIEW）由编排器负责 */
@Component
@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)
public class MockPublisher implements Publisher {

    @Override
    public PublishResult publish(Work work, PublishAccount account) {
        if (chance(0.9)) {
            return new PublishResult(true, null);
        }
        return new PublishResult(false, "平台审核未通过（模拟）：标题含敏感词");
    }
}
