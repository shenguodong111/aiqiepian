package com.aiqiepian.engine;

import com.aiqiepian.entity.Work;
import com.aiqiepian.engine.model.RenderResult;

/** 环节 04 成片（需求文档 §7）。真实实现替换：ffmpeg 裁剪 + 字幕/封面包装。 */
public interface Renderer {

    /**
     * 渲染一个作品。作品的 {@code reelSegments} 非空时，真实引擎按多区间裁切拼接成高光集锦；
     * 否则按单一切片裁剪。模拟引擎两者都返回占位路径。
     */
    RenderResult render(Work work);
}
