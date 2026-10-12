package com.aiqiepian.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** StreamMonitor.check 输出：开播/未开播事件（需求文档 §7） */
@Data
@NoArgsConstructor
public class LiveEvent {
    private boolean live;
    /** 真实引擎附带房间直播标题（mock 引擎为 null，不影响 mock 行为） */
    private String title;

    /** 兼容 mock 引擎的既有单参构造 */
    public LiveEvent(boolean live) {
        this.live = live;
    }

    public LiveEvent(boolean live, String title) {
        this.live = live;
        this.title = title;
    }
}
