package com.aiqiepian.service;

import java.util.Map;

/** 跨机迁移：直播间列表 + 设置导出为 JSON，可在另一台机器导入还原。 */
public interface ConfigSyncService {

    /** 导出配置；includeHistory 为真时附带录制会话/片段/作品记录（不含媒体文件本身） */
    Map<String, Object> exportConfig(boolean includeHistory);

    /** 导入 export() 产出的 JSON：房间按 room_url 新增或覆盖，设置合并，历史按去重键跳过已有 */
    Map<String, Object> importConfig(Map<String, Object> payload);
}
