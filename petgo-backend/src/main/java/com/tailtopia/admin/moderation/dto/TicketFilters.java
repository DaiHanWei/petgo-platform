package com.tailtopia.admin.moderation.dto;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A2 被举报用户工作台的筛选上下文（V1.3.0 Story 2.5 AC1）：待处置 / 已处置两态、举报类型、关键词（账号 id / 昵称）、页码。
 * 本页只管 {@link TicketType#ACCOUNT_REPORT}。处置 POST 不新增请求参数：htmx 请求从 {@code HX-Current-URL} 还原筛选算「下一条」。
 */
public record TicketFilters(boolean handled, String reason, String q, int page) {

    public static final TicketFilters DEFAULT = new TicketFilters(false, null, null, 0);

    /** {@code state=pending|handled} 优先；旧链接 {@code ?status=RESOLVED|NO_ACTION} 映射到已处置态。 */
    public static TicketFilters of(String state, String status, String reason, String q, Integer page) {
        boolean handled = "handled".equalsIgnoreCase(state)
                || (state == null && status != null && !status.isBlank() && !"PENDING".equalsIgnoreCase(status));
        return new TicketFilters(handled, blank(reason), blank(q), page == null ? 0 : Math.max(page, 0));
    }

    public static TicketFilters fromUrl(String url) {
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
            return of(m.get("state"), m.get("status"), m.get("reason"), m.get("q"), page);
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    public String state() {
        return handled ? "handled" : "pending";
    }

    public TicketFilters withPage(int p) {
        return new TicketFilters(handled, reason, q, Math.max(p, 0));
    }

    /** 处置后算下一条：回到待处置第一页（其余筛选保留）。 */
    public TicketFilters pendingFirstPage() {
        return new TicketFilters(false, reason, q, 0);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
