package com.aiqiepian.service.impl;

import com.aiqiepian.entity.AppSetting;
import com.aiqiepian.mapper.AppSettingMapper;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.mapper.PipelineJobMapper;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.mapper.ReviewRecordMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * app_setting KV 存储：value 列存 JSON 标量字符串（与文档 §5 "JSON 值" 一致）。
 * 默认值与 mock-server db.settings 完全一致；启动时若表为空则播种。
 */
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements SettingsService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsServiceImpl.class);

    /** 非设置项的内部 KV 键（不出现在 GET /settings 响应中） */
    public static final String KEY_PUBLISH_ACCOUNT = "publish_account";

    /** M3 敏感设置（Cookie 三件套）：GET 返回时掩码，PUT 收到掩码值不覆盖真实值 */
    private static final Set<String> SENSITIVE_KEYS = Set.of("bili_sessdata", "bili_jct", "bili_uid", "bili_browser_cookie");

    /** 有序默认设置，键与 server.mjs db.settings 一一对应 */
    private static final Map<String, Object> DEFAULTS = buildDefaults();

    private final AppSettingMapper appSettingMapper;
    private final ObjectMapper objectMapper;
    private final LiveRoomMapper liveRoomMapper;
    private final RecordingSessionMapper recordingSessionMapper;
    private final ClipMapper clipMapper;
    private final WorkMapper workMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final PublishTaskMapper publishTaskMapper;
    private final PipelineJobMapper pipelineJobMapper;

    /** 轻量缓存，写设置时失效 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;

    /** 引擎模式以 application.yml / 启动参数为准（优先于 app_setting 中的展示键 engine_mode） */
    @Value("${engine.mode:mock}")
    private String engineMode;

    private static Map<String, Object> buildDefaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auto_run", true);
        m.put("monitor_interval_sec", 10);
        m.put("max_concurrent_record", 3);
        m.put("max_concurrent_analyze", 2);
        m.put("max_concurrent_render", 2);
        m.put("max_concurrent_heavy", 2);
        m.put("max_retry", 3);
        m.put("auto_pass_score", 85);
        m.put("auto_reject_score", 70);
        m.put("auto_work_min_score", 70);
        // ---- 高光集锦自动成片（engine.mode=real 生效）：整录分析后自动选段拼成一支 ≤10-20min 成片 ----
        m.put("reel_enabled", true);
        m.put("sensitive_words", "");
        m.put("manual_on_uncertain", true);
        m.put("auto_publish", true);
        m.put("media_dir", "./media");
        m.put("keep_days", 30);
        m.put("ai_mode", "mock");
        m.put("ai_local_url", "http://localhost:11434");
        m.put("ai_local_model", "qwen2.5:3b");
        // ---- 本地语音 ASR 字幕（faster-whisper，engine.mode=real 生效）----
        m.put("asr_enabled", true);
        m.put("asr_python", "C:/Users/25436/AppData/Local/Programs/Python/Python313/python.exe");
        m.put("asr_model", "small");
        m.put("asr_language", "zh");
        m.put("asr_device", "cpu");
        m.put("asr_compute", "int8");
        m.put("publish_default_tags", "直播切片,AI剪辑");
        m.put("publish_desc_template", "{房间} {日期} 的精彩瞬间，由 AI 切片自动生成。");
        m.put("engine_mode", "mock");
        m.put("max_record_minutes", 240);
        // ---- M3 真实发布引擎（engine.mode=real，Cookie 方案）----
        m.put("bili_sessdata", "");
        m.put("bili_jct", "");
        m.put("bili_uid", "");
        m.put("bili_browser_cookie", "");
        m.put("publish_tid", 234);
        m.put("publish_cover_url", "");
        return m;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (appSettingMapper.selectCount(null) == 0) {
            DEFAULTS.forEach(this::setValue);
            log.info("app_setting 为空，已播种 {} 项默认设置", DEFAULTS.size());
        }
    }

    @Override
    public Map<String, Object> getAll() {
        Map<String, String> raw = loadRaw();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : DEFAULTS.keySet()) {
            result.put(key, parse(raw.getOrDefault(key, null), DEFAULTS.get(key)));
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (DEFAULTS.containsKey(e.getKey()) || KEY_PUBLISH_ACCOUNT.equals(e.getKey())) continue;
            result.put(e.getKey(), parse(e.getValue(), null));
        }
        // engine_mode 展示值以 application.yml / 启动参数中的 engine.mode 为准（优先于 DB）
        result.put("engine_mode", engineMode);
        // 敏感键（Cookie）对外只回掩码值
        for (String key : SENSITIVE_KEYS) {
            if (result.containsKey(key)) result.put(key, mask(String.valueOf(result.get(key))));
        }
        return result;
    }

    @Override
    public Map<String, Object> updateAll(Map<String, Object> patch) {
        if (patch != null) patch.forEach((k, v) -> {
            if (SENSITIVE_KEYS.contains(k) && isMaskedValue(v)) {
                // 前端回填的掩码值（含 '*'）：保持原值，不覆盖真实 Cookie
                return;
            }
            setValue(k, v);
        });
        return getAll();
    }

    /** 判断是否为掩码值（GET 回显后被前端原样 PUT 回来） */
    private static boolean isMaskedValue(Object v) {
        return v instanceof String s && s.contains("*");
    }

    /** 掩码：只留前后 4 位 */
    public static String mask(String v) {
        if (v == null || v.isEmpty() || "null".equals(v)) return "";
        if (v.length() <= 8) return "********";
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    @Override
    public boolean getBool(String key, boolean def) {
        Object v = rawValue(key);
        return v instanceof Boolean b ? b : (v == null ? def : Boolean.parseBoolean(String.valueOf(v)));
    }

    @Override
    public int getInt(String key, int def) {
        Object v = rawValue(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    @Override
    public String getStr(String key, String def) {
        Object v = rawValue(key);
        return v == null ? def : String.valueOf(v);
    }

    /** 内部读取：取未掩码的原始值（getAll 会脱敏，内部消费方必须走这里） */
    private Object rawValue(String key) {
        return parse(loadRaw().get(key), DEFAULTS.get(key));
    }

    @Override
    public void setValue(String key, Object value) {
        try {
            putRaw(key, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("设置值序列化失败: " + key, e);
        }
    }

    @Override
    public void setJson(String key, Object value) {
        setValue(key, value);
    }

    @Override
    public <T> T getJson(String key, Class<T> type) {
        String raw = loadRaw().get(key);
        if (raw == null) return null;
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.warn("设置值反序列化失败 key={}", key, e);
            return null;
        }
    }

    @Override
    public void remove(String key) {
        cacheLoaded = false;
        cache.remove(key);
        appSettingMapper.deleteById(key);
    }

    @Override
    public void resetDemoData() {
        List<BaseMapper<?>> mappers = new ArrayList<>();
        mappers.add(pipelineJobMapper);
        mappers.add(publishTaskMapper);
        mappers.add(reviewRecordMapper);
        mappers.add(workMapper);
        mappers.add(clipMapper);
        mappers.add(recordingSessionMapper);
        mappers.add(liveRoomMapper);
        for (BaseMapper<?> m : mappers) {
            m.delete(null);
        }
        remove(KEY_PUBLISH_ACCOUNT);
        log.info("演示数据已重置（保留设置）");
    }

    // ---------- 内部 ----------

    private void putRaw(String key, String jsonValue) {
        cacheLoaded = false;
        cache.remove(key);
        AppSetting existing = appSettingMapper.selectById(key);
        AppSetting s = new AppSetting();
        s.setKey(key);
        s.setValue(jsonValue);
        if (existing == null) {
            appSettingMapper.insert(s);
        } else {
            appSettingMapper.updateById(s);
        }
    }

    private Map<String, String> loadRaw() {
        if (!cacheLoaded) {
            Map<String, String> fresh = new ConcurrentHashMap<>();
            for (AppSetting s : appSettingMapper.selectList(null)) {
                fresh.put(s.getKey(), s.getValue());
            }
            cache.keySet().retainAll(fresh.keySet());
            cache.putAll(fresh);
            cacheLoaded = true;
        }
        return cache;
    }

    private Object parse(String raw, Object fallback) {
        if (raw == null) return fallback;
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
