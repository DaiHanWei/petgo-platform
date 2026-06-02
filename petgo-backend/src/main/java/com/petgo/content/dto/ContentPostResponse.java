package com.petgo.content.dto;

import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import java.time.Instant;
import java.util.List;

/**
 * 内容帖子响应。Jackson NON_NULL；时间 ISO-8601 UTC。
 */
public record ContentPostResponse(
        Long id,
        ContentType type,
        Long petId,
        String text,
        List<String> imageUrls,
        String dangerLevel,
        Instant createdAt) {

    public static ContentPostResponse from(ContentPost p) {
        return new ContentPostResponse(
                p.getId(),
                p.getType(),
                p.getPetId(),
                p.getText(),
                p.getImageUrls(),
                p.getDangerLevel(),
                p.getCreatedAt());
    }
}
