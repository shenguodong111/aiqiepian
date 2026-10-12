package com.aiqiepian.service;

import java.util.Map;

public interface SettingsService {

    /** 全量 KV（类型还原后的值），键与 mock-server db.settings 一致 */
    Map<String, Object> getAll();

    /** 合并式更新（PUT /settings 整包） */
    Map<String, Object> updateAll(Map<String, Object> patch);

    boolean getBool(String key, boolean def);

    int getInt(String key, int def);

    String getStr(String key, String def);

    void setValue(String key, Object value);

    /** 存 JSON 对象（如 publish_account） */
    void setJson(String key, Object value);

    <T> T getJson(String key, Class<T> type);

    void remove(String key);

    /** 重置演示数据：清空业务表与发布账号（保留设置，与 mock 一致） */
    void resetDemoData();
}
