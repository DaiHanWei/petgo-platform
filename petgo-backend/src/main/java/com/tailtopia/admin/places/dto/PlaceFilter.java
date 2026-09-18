package com.tailtopia.admin.places.dto;

import com.tailtopia.admin.places.domain.PlaceStatus;

/**
 * B6 场所列表筛选（V1.3.0 Story 5.2 AC2 + D-39 城市）：关键词（name / address_text ILIKE）· 类型 · 状态 · 城市 · 页码。
 * 空串一律归 null；status 非法归 null（全部）。
 */
public record PlaceFilter(String q, String type, PlaceStatus status, String city, int page) {

    public static final int PAGE_SIZE = 20;

    public static PlaceFilter of(String q, String type, String status, String city, Integer page) {
        return new PlaceFilter(blankToNull(q), blankToNull(type), parseStatus(status), blankToNull(city),
                page == null || page < 0 ? 0 : page);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static PlaceStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PlaceStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 模板回填用：状态原文（null → 空串 = 全部）。 */
    public String statusParam() {
        return status == null ? "" : status.name();
    }
}
