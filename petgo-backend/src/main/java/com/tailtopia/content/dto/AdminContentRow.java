package com.tailtopia.content.dto;

import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.util.List;

/**
 * 后台全量内容浏览行投影（Story 4.2）。content 模块对外只读 DTO（admin 不直拿 ContentPost 实体）。
 * {@code deleted} = {@code deleted_at IS NOT NULL}（含作者自删/注销级联/运营下架，无 delete_reason 列区分）。
 * {@code imageUrls} 为公开桶 CDN 全 URL 列表（可空），供后台直接预览内容图（bug 20260630-157）。
 * {@code commentCount} = 该帖未删评论数（含一级+二级，不按 viewer 过滤——后台全量口径）。
 */
public record AdminContentRow(long id, ContentType type, Long authorId, String textPreview,
        boolean deleted, Instant createdAt, List<String> imageUrls, long commentCount) {

    /** 兼容旧调用（评论数=0）。 */
    public AdminContentRow(long id, ContentType type, Long authorId, String textPreview,
            boolean deleted, Instant createdAt, List<String> imageUrls) {
        this(id, type, authorId, textPreview, deleted, createdAt, imageUrls, 0L);
    }
}
