package com.tailtopia.content.domain;

import com.tailtopia.shared.error.AppException;

/**
 * Feed 分类 Tab（Story 3.2，AC3）。{@link #ALL} 不限 type；其余按 {@link ContentType} 精确过滤。
 * {@code GROWTH_MOMENT} 分类额外要求 {@code pet_id IS NOT NULL}（仅有宠物档案的帖）。
 */
public enum FeedCategory {
    ALL,
    DAILY,
    GROWTH_MOMENT,
    KNOWLEDGE;

    public static FeedCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("无效的内容分类");
        }
    }

    /** 该分类对应的具体内容类型（{@link #ALL} 返回 null = 不限）。 */
    public ContentType toContentType() {
        return this == ALL ? null : ContentType.valueOf(name());
    }

    /** 是否要求帖子绑定了宠物档案（仅成长日历分类）。 */
    public boolean requiresPet() {
        return this == GROWTH_MOMENT;
    }
}
