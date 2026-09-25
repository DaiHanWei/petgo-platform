package com.tailtopia.admin.shared.web;

/**
 * 模板 A 页签行的一项（V1.3.0 Story 2.9 AC2，公共片段 {@code tpl-a-state-tabs :: tabs(tabs)}）。
 *
 * @param href     整页跳转链接（保留筛选参数由各页 Controller 拼）
 * @param labelKey i18n key
 * @param countId  计数元素 id（处置 fragment 以同 id oob 替换）
 * @param count    待处理数（与角标同源，Story 2.9 AC1）
 * @param on       当前页签
 */
public record StateTab(String href, String labelKey, String countId, long count, boolean on) {

    /** 拼整页链接：{@code base?k1=v1&k2=v2}（null / 空值跳过，值 URL 编码）。 */
    public static String href(String base, Object... kv) {
        var b = org.springframework.web.util.UriComponentsBuilder.fromPath(base);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v != null && !String.valueOf(v).isBlank()) {
                b.queryParam(String.valueOf(kv[i]), String.valueOf(v));
            }
        }
        return b.encode().build().toUriString();
    }
}
