package com.tailtopia.content.dto;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 内容帖子响应。Jackson NON_NULL；时间 ISO-8601 UTC。
 * {@code eventDate}（F9）仅 GROWTH_MOMENT 有值，回显发布的成长日历事件日期。
 *
 * <p>{@code topics}（V1.4.2 social-topic Story 4.1 · AC6.3 · R-2）：本帖写入的话题（标准名 + 是否官方 + 是否本次新建），
 * <b>末尾追加</b>；无话题 / 私密帖为 null（NON_NULL 不下发，响应体逐字节不变）。
 */
public record ContentPostResponse(
        Long id,
        ContentType type,
        Long petId,
        String text,
        List<String> imageUrls,
        String dangerLevel,
        LocalDate eventDate,
        Instant createdAt,
        List<TopicRef> topics) {

    public ContentPostResponse {
        topics = (topics == null || topics.isEmpty()) ? null : List.copyOf(topics);
    }

    /** 兼容无事件日期的构造（既有调用点 / 测试桩）：eventDate 省为 null。 */
    public ContentPostResponse(Long id, ContentType type, Long petId, String text,
            List<String> imageUrls, String dangerLevel, Instant createdAt) {
        this(id, type, petId, text, imageUrls, dangerLevel, null, createdAt, null);
    }

    /** 8 参兼容构造（V1.4.2 Story 4.1 前的规范形态）：{@code topics} 省为 null。 */
    public ContentPostResponse(Long id, ContentType type, Long petId, String text,
            List<String> imageUrls, String dangerLevel, LocalDate eventDate, Instant createdAt) {
        this(id, type, petId, text, imageUrls, dangerLevel, eventDate, createdAt, null);
    }

    /** 不带话题（{@code topics=null}）。 */
    public static ContentPostResponse from(ContentPost p) {
        return from(p, null);
    }

    /** 带本帖写入的话题（空表 / null → 不下发）。 */
    public static ContentPostResponse from(ContentPost p, List<TopicRef> topics) {
        return new ContentPostResponse(
                p.getId(),
                p.getType(),
                p.getPetId(),
                p.getText(),
                p.getImageUrls(),
                p.getDangerLevel(),
                p.getEventDate(),
                p.getCreatedAt(),
                topics);
    }
}
