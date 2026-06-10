package com.tailtopia.content.dto;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 内容帖子响应。Jackson NON_NULL；时间 ISO-8601 UTC。
 * {@code eventDate}（F9）仅 GROWTH_MOMENT 有值，回显发布的成长日历事件日期。
 */
public record ContentPostResponse(
        Long id,
        ContentType type,
        Long petId,
        String text,
        List<String> imageUrls,
        String dangerLevel,
        LocalDate eventDate,
        Instant createdAt) {

    /** 兼容无事件日期的构造（既有调用点 / 测试桩）：eventDate 省为 null。 */
    public ContentPostResponse(Long id, ContentType type, Long petId, String text,
            List<String> imageUrls, String dangerLevel, Instant createdAt) {
        this(id, type, petId, text, imageUrls, dangerLevel, null, createdAt);
    }

    public static ContentPostResponse from(ContentPost p) {
        return new ContentPostResponse(
                p.getId(),
                p.getType(),
                p.getPetId(),
                p.getText(),
                p.getImageUrls(),
                p.getDangerLevel(),
                p.getEventDate(),
                p.getCreatedAt());
    }
}
