package com.aiqiepian.engine.mock;

import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.engine.Recorder;
import com.aiqiepian.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.aiqiepian.engine.mock.MockSupport.rnd;

/** 模拟录制：假文件路径 + 目标时长（手动 25-45s、自动 30-90s，演示加速值） */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)
public class MockRecorder implements Recorder {

    private final SettingsService settings;

    @Override
    public void start(RecordingSession session, boolean manual) {
        String dir = settings.getStr("media_dir", "./media");
        session.setDurationSec(manual ? rnd(25, 45) : rnd(30, 90));
        session.setRecordedSec(0);
        session.setFileSizeBytes(0L);
        String label = session.getRoomName() != null && !session.getRoomName().isEmpty()
                ? session.getRoomName() : String.valueOf(session.getRoomId());
        session.setFilePath(dir + "/recordings/" + label + "_" + System.currentTimeMillis() + ".mp4");
    }

    @Override
    public void stop(RecordingSession session) {
        session.setFileSizeBytes((long) session.getDurationSec() * rnd(400_000, 900_000));
    }
}
