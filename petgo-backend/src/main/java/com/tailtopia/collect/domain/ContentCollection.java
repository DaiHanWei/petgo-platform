package com.tailtopia.collect.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 帖子收藏（V1.4.0 ④ Story 2.1 · AD-7）。用户×帖子唯一（{@code uq_content_collections_user_post}）。
 *
 * <p>🔴 写入不走 {@code save()}：一律 {@code insertIfAbsent}（{@code INSERT … ON CONFLICT DO NOTHING}，按影响行数判断是否真正新增）。
 * 实体只供列表读取（Story 2.2）与 {@code ddl-auto=validate}；{@code created_at} 由库默认值填。
 */
@Entity
@Table(name = "content_collections")
public class ContentCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ContentCollection() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPostId() {
        return postId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
