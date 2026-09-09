package com.tailtopia.admin.moderation.dto;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 工作台筛选上下文（V1.3.0 Story 2.4）：页签 / 待处理·已处理两态 / 子类型 / 优先级 / 违规类别 / 关键词 / 页码。
 * 处置 POST 不新增任何请求参数（AC4 红线）：htmx 请求从 {@code HX-Current-URL} 头解析出当前筛选，据此算「下一条」与页签计数。
 */
public record ReviewFilters(ReviewTab tab, boolean handled, String subType, String priority, String category,
        String q, int page) {

    public static final ReviewFilters DEFAULT = new ReviewFilters(ReviewTab.SUBMISSION, false, null, null, null, null, 0);

    public static ReviewFilters of(String tab, String type, String state, String status, String subType,
            String priority, String category, String q, Integer page) {
        ReviewTab t = tab != null && !tab.isBlank() ? ReviewTab.fromParam(tab) : ReviewTab.fromLegacyType(type);
        if (t == null) {
            t = ReviewTab.SUBMISSION;
        }
        boolean handled = "handled".equalsIgnoreCase(state)
                || (state == null && status != null && !status.isBlank() && !"PENDING".equalsIgnoreCase(status));
        return new ReviewFilters(t, handled, blank(subType), blank(priority), blank(category), blank(q),
                page == null ? 0 : Math.max(page, 0));
    }

    /** 从 {@code HX-Current-URL}（或任意 URL）解析；解析不到 → 默认页签待处理。 */
    public static ReviewFilters fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT;
        }
        try {
            String query = URI.create(url.trim()).getRawQuery();
            Map<String, String> m = new HashMap<>();
            if (query != null) {
                for (String kv : query.split("&")) {
                    int i = kv.indexOf('=');
                    String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                    String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                    m.putIfAbsent(k, v);
                }
            }
            Integer page = null;
            try {
                page = m.get("page") == null ? null : Integer.parseInt(m.get("page"));
            } catch (NumberFormatException ignore) {
                // 非法页码当第一页
            }
            return of(m.get("tab"), m.get("type"), m.get("state"), m.get("status"), m.get("subType"),
                    m.get("priority"), m.get("category"), m.get("q"), page);
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    public String state() {
        return handled ? "handled" : "pending";
    }

    public ReviewFilters withTab(ReviewTab t) {
        return new ReviewFilters(t, handled, subType, priority, category, q, 0);
    }

    public ReviewFilters withPage(int p) {
        return new ReviewFilters(tab, handled, subType, priority, category, q, Math.max(p, 0));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
