package com.tailtopia.admin.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * fragment 响应辅助（Story 2.3a AC5）：写 {@code HX-Trigger} 响应头，多事件合并成一个 JSON 对象
 * （{@code {"admin:badge-refresh":{},"admin:drawer-close":{}}}），重复调用累积不覆盖。
 */
public final class AdminFragmentResponses {

    public static final String HEADER_TRIGGER = "HX-Trigger";
    public static final String HEADER_RESWAP = "HX-Reswap";
    public static final String HEADER_RETARGET = "HX-Retarget";

    private AdminFragmentResponses() {
    }

    /** 触发侧栏待办角标刷新（写操作成功后调）。 */
    public static void triggerBadgeRefresh(HttpServletResponse response) {
        trigger(response, AdminHxEvents.BADGE_REFRESH);
    }

    /** 追加一个或多个事件到 {@code HX-Trigger}（保留已有，含已有事件的载荷）。 */
    public static void trigger(HttpServletResponse response, String... events) {
        Map<String, String> all = parseEntries(response.getHeader(HEADER_TRIGGER));
        for (String e : events) {
            if (e != null && !e.isBlank()) {
                all.putIfAbsent(e, "{}");
            }
        }
        response.setHeader(HEADER_TRIGGER, toJsonEntries(all));
    }

    /**
     * 追加一个<b>带载荷</b>的事件（htmx 的 {@code HX-Trigger} 支持 {@code {"evt":{...}}}，
     * 载荷进 {@code event.detail}）。Story 7.4 用它把新建好的标签抽屉 URL 交给前端。
     *
     * @param payloadJson 一个 JSON 对象字面量（如 {@code {"url":"/admin/..."}}）；null / 空按 {@code {}} 处理
     */
    public static void trigger(HttpServletResponse response, String event, String payloadJson) {
        if (event == null || event.isBlank()) {
            return;
        }
        Map<String, String> all = parseEntries(response.getHeader(HEADER_TRIGGER));
        all.put(event, payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson.trim());
        response.setHeader(HEADER_TRIGGER, toJsonEntries(all));
    }

    static Set<String> parse(String header) {
        return new LinkedHashSet<>(parseEntries(header).keySet());
    }

    /**
     * 解析已有的 {@code HX-Trigger}：事件名 → 载荷 JSON 原文。
     *
     * <p>⚠️ 必须把载荷一起带回来 —— 只留事件名的话，先写「带 URL 的 drawer-open」再追加一个普通事件，
     * 就会把那个 URL 悄悄抹掉，而界面上的表现只是「新建完抽屉没打开」，很难往响应头上想。
     */
    static Map<String, String> parseEntries(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null || header.isBlank()) {
            return out;
        }
        String h = header.trim();
        if (!h.startsWith("{")) {
            for (String p : h.split(",")) {
                if (!p.isBlank()) {
                    out.put(p.trim(), "{}");
                }
            }
            return out;
        }
        // {"a":{},"b":{"x":1}} → 只取深度 1 的 key（按花括号深度扫描，嵌套对象的 key 不算事件名）
        int depth = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == '"' && depth == 1) {
                int end = h.indexOf('"', i + 1);
                if (end < 0) {
                    break;
                }
                int colon = end + 1;
                while (colon < h.length() && Character.isWhitespace(h.charAt(colon))) {
                    colon++;
                }
                if (colon < h.length() && h.charAt(colon) == ':') {
                    String name = h.substring(i + 1, end);
                    int valueStart = colon + 1;
                    while (valueStart < h.length() && Character.isWhitespace(h.charAt(valueStart))) {
                        valueStart++;
                    }
                    int valueEnd = valueEnd(h, valueStart);
                    out.put(name, h.substring(valueStart, valueEnd).trim());
                    i = valueEnd - 1;
                } else {
                    i = end;
                }
            }
        }
        return out;
    }

    /** 载荷值的结束下标（不含）：对象按花括号配平（跳过字符串内的括号），标量读到深度 1 的逗号 / 收尾花括号。 */
    private static int valueEnd(String h, int start) {
        int i = start;
        int depth = 0;
        boolean inString = false;
        for (; i < h.length(); i++) {
            char c = h.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (depth == 0) {
                    return i; // 整个 HX-Trigger 对象的收尾花括号
                }
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return i;
    }

    static String toJson(Set<String> events) {
        Map<String, String> all = new LinkedHashMap<>();
        events.forEach(e -> all.put(e, "{}"));
        return toJsonEntries(all);
    }

    private static String toJsonEntries(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "")).append("\":")
                    .append(e.getValue() == null || e.getValue().isBlank() ? "{}" : e.getValue());
        }
        return sb.append('}').toString();
    }
}
