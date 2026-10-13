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

    /**
     * 只重建封面，不重编视频：改了标题或换了底图后让封面文字跟着变。
     * 真实引擎按「作品手工底图 &gt; 房间素材 &gt; 源视频截帧」重新叠字出图；模拟引擎无封面概念，默认空实现。
     */
    default void buildCover(Work work) {
    }
}
