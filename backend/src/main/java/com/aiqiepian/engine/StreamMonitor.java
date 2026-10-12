package com.aiqiepian.engine;

import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.engine.model.LiveEvent;

/** 环节 01 监控（需求文档 §7）。真实实现替换：解析直播平台房间页/接口判断开播。 */
public interface StreamMonitor {

    /** 管线自动轮询用（mock 概率 0.18） */
    default LiveEvent check(LiveRoom room) {
        return check(room, false);
    }

    /** manual=true 为页面"立即检查一次"（mock 概率 0.5） */
    LiveEvent check(LiveRoom room, boolean manual);
}
