package com.tailtopia.admin.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashSet;
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

    /** 追加一个或多个事件到 {@code HX-Trigger}（保留已有）。 */
    public static void trigger(HttpServletResponse response, String... events) {
        Set<String> all = new LinkedHashSet<>(parse(response.getHeader(HEADER_TRIGGER)));
        for (String e : events) {
            if (e != null && !e.isBlank()) {
                all.add(e);
            }
        }
        response.setHeader(HEADER_TRIGGER, toJson(all));
    }

    static Set<String> parse(String header) {
        Set<String> out = new LinkedHashSet<>();
        if (header == null || header.isBlank()) {
            return out;
        }
        String h = header.trim();
        if (h.startsWith("{")) {
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
                        out.add(h.substring(i + 1, end));
                    }
                    i = end;
                }
            }
        } else {
            for (String p : h.split(",")) {
                if (!p.isBlank()) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    static String toJson(Set<String> events) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String e : events) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.replace("\"", "")).append("\":{}");
        }
        return sb.append('}').toString();
    }
}
