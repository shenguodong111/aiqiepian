package com.aiqiepian.service;

import java.util.Map;

/** 播放量同步（功能 M4-3）：对已发布真实 BV 稿件回拉 view/like/danmaku */
public interface ViewCountService {

    /**
     * 同步一轮：status=PUBLISHED 且 remote_id 为真实 BV 号（排除 BV1mock*）的任务，
     * GET https://api.bilibili.com/x/web-interface/view?bvid={bv}（公开接口，无需 Cookie）。
     *
     * @return {synced, failed}
     */
    Map<String, Object> syncAll();
}
