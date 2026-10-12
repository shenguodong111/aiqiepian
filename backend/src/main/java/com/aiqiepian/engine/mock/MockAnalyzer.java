package com.aiqiepian.engine.mock;

import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.engine.Analyzer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.aiqiepian.engine.mock.MockSupport.pick;
import static com.aiqiepian.engine.mock.MockSupport.rnd;

/** 模拟分析：每会话随机 3-8 个片段，评分 60-98（对照 server.mjs analyzeDone） */
@Component
@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)
public class MockAnalyzer implements Analyzer {

    private static final List<String> REASONS = List.of(
            "主播情绪高涨，弹幕密度骤增",
            "抽奖/福利环节，观众互动峰值",
            "高光操作配合连续礼物特效",
            "争议观点引发弹幕讨论",
            "名场面复刻，弹幕刷屏",
            "连麦笑点密集段落");

    @Override
    public List<Clip> analyze(RecordingSession session) {
        int n = rnd(3, 8);
        int span = Math.max(1, session.getDurationSec() * 60 - 180);
        List<Clip> clips = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int start = rnd(0, span);
            Clip clip = new Clip();
            clip.setSessionId(session.getId());
            clip.setRoomId(session.getRoomId());
            clip.setRoomName(session.getRoomName());
            clip.setStartSec(start);
            clip.setEndSec(start + rnd(30, 150));
            clip.setDurationSec(clip.getEndSec() - clip.getStartSec());
            clip.setScore(rnd(60, 98));
            clip.setAiReason(pick(REASONS));
            clip.setDanmakuHeat(rnd(200, 9999));
            clip.setStatus("PENDING");
            clips.add(clip);
        }
        return clips;
    }
}
