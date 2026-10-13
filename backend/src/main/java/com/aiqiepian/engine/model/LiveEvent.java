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
    /**
     * 平台是否明确应答。false 表示接口失败/超时/被限流，直播状态**未知**——
     * 绝不能据此判定下播，否则一次网络抖动就会打断正在进行的录制。
     */
    private boolean known = true;

    /** 兼容 mock 引擎的既有单参构造 */
    public LiveEvent(boolean live) {
        this.live = live;
    }

    public LiveEvent(boolean live, String title) {
        this.live = live;
        this.title = title;
    }

    /** 查询失败：状态未知，调用方应保持现状 */
    public static LiveEvent unknown() {
        LiveEvent e = new LiveEvent(false);
        e.known = false;
        return e;
    }
}
