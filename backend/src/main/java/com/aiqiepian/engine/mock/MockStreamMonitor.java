package com.aiqiepian.engine.mock;

import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.engine.StreamMonitor;
import com.aiqiepian.engine.model.LiveEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.aiqiepian.engine.mock.MockSupport.chance;

/** 模拟监控：管线轮询 18% 概率开播；页面手动检查 50% 概率（对照 server.mjs） */
@Component
@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)
public class MockStreamMonitor implements StreamMonitor {

    @Override
    public LiveEvent check(LiveRoom room, boolean manual) {
        return new LiveEvent(chance(manual ? 0.5 : 0.18));
    }
}
