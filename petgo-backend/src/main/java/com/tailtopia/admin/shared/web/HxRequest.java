package com.tailtopia.admin.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * htmx 请求判别（V1.3.0 Story 2.3a，AD-9）。Controller 方法声明 {@code HxRequest hx} 参数即可拿到
 * （{@link HxRequestArgumentResolver}）；同一方法两条路径共用 Model：
 * {@code hx.isHtmx() ? "admin/fragments/xxx :: yyy" : "admin/xxx"}。
 *
 * @param htmx       请求头 {@code HX-Request: true}
 * @param target     {@code HX-Target}（触发元素的 hx-target id，可空）
 * @param trigger    {@code HX-Trigger}（触发元素 id，可空）
 * @param currentUrl {@code HX-Current-URL}（可空）
 */
public record HxRequest(boolean htmx, String target, String trigger, String currentUrl) {

    public static final String HEADER_REQUEST = "HX-Request";
    public static final String HEADER_TARGET = "HX-Target";
    public static final String HEADER_TRIGGER = "HX-Trigger";
    public static final String HEADER_CURRENT_URL = "HX-Current-URL";

    public static final HxRequest NONE = new HxRequest(false, null, null, null);

    /** 从请求头解析（过滤器 / advice 复用）。 */
    public static HxRequest of(HttpServletRequest request) {
        if (request == null || !"true".equalsIgnoreCase(request.getHeader(HEADER_REQUEST))) {
            return NONE;
        }
        return new HxRequest(true, blankToNull(request.getHeader(HEADER_TARGET)),
                blankToNull(request.getHeader(HEADER_TRIGGER)), blankToNull(request.getHeader(HEADER_CURRENT_URL)));
    }

    public boolean isHtmx() {
        return htmx;
    }

    /** 二选一：htmx 走 fragment 视图名，否则整页视图名。 */
    public String view(String fragmentView, String pageView) {
        return htmx ? fragmentView : pageView;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
