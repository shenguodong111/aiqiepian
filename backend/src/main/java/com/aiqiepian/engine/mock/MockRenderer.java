package com.aiqiepian.engine.mock;

import com.aiqiepian.entity.Work;
import com.aiqiepian.engine.Renderer;
import com.aiqiepian.engine.model.RenderResult;
import com.aiqiepian.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 模拟成片：产出假产物路径（进度推进由 PipelineOrchestrator 的 job 状态机负责） */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)
public class MockRenderer implements Renderer {

    private final SettingsService settings;

    @Override
    public RenderResult render(Work work) {
        String dir = settings.getStr("media_dir", "./media");
        return new RenderResult(dir + "/works/work_" + work.getId() + ".mp4");
    }
}
