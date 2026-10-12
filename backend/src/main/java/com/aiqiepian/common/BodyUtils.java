package com.aiqiepian.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 请求体（Map，snake_case key）取值工具：模拟 mock-server 中 `body.x ?? default` 语义。
 */
public final class BodyUtils {

    private BodyUtils() {
    }

    public static String str(Map<String, Object> body, String key, String def) {
        Object v = body == null ? null : body.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static Integer intVal(Map<String, Object> body, String key, Integer def) {
        Object v = body == null ? null : body.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    public static Long longVal(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    public static Boolean boolVal(Map<String, Object> body, String key, Boolean def) {
        Object v = body == null ? null : body.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return !s.isEmpty() && !"false".equals(s);
        return def;
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    /** 从 body 取一个 id 数组（Number 或数字字符串），忽略非法项；无则空列表 */
    public static List<Long> longList(Map<String, Object> body, String key) {
        List<Long> out = new ArrayList<>();
        Object raw = body == null ? null : body.get(key);
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Number n) out.add(n.longValue());
                else if (o != null) {
                    try { out.add(Long.parseLong(String.valueOf(o).trim())); } catch (NumberFormatException ignored) { }
                }
            }
        }
        return out;
    }
}
