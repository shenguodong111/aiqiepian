package com.aiqiepian.service;

import com.aiqiepian.common.PageResult;
import com.aiqiepian.entity.LiveRoom;

import java.util.Map;

public interface RoomService {

    PageResult<LiveRoom> list(String platform, String status, String kw, Integer page, Integer size);

    LiveRoom create(Map<String, Object> body);

    LiveRoom update(long id, Map<String, Object> body);

    void delete(long id);

    LiveRoom toggle(long id, boolean enabled);

    /** 立即检查开播，返回 { live: bool }；若开播则同时创建录制会话（与 mock 一致） */
    Map<String, Object> check(long id);
}
