package com.tailtopia.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 内容浏览记录（2026-08-31）—— 每「内容 × 观看者」一行。
 *
 * <p>浏览次数 = 各行 {@code viewCount} 之和；浏览人数 = 行数。
 * 观看者键：登录用户 {@code u:<userId>}、游客 {@code a:<匿名会话id>}（{@code uq_content_post_views_post_viewer} 去重）。
 *
 * <p>⚠️ 写入不走本实体的 save（读改写并发下会丢加），走仓储的原生 UPSERT ——
 * 本实体存在的意义是让 {@code ddl-auto=validate} 校验 schema 与给聚合查询一个挂靠点。
 */
@Entity
@Table(name = "content_post_views")
public class ContentPostView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "viewer_key", nullable = false, length = 40)
    private String viewerKey;

    @Column(name = "view_count", nullable = false)
    private Long viewCount;

    @Column(name = "first_viewed_at", nullable = false, updatable = false)
    private Instant firstViewedAt;

    @Column(name = "last_viewed_at", nullable = false)
    private Instant lastViewedAt;

    protected ContentPostView() {
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public String getViewerKey() {
        return viewerKey;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public Instant getFirstViewedAt() {
        return firstViewedAt;
    }

    public Instant getLastViewedAt() {
        return lastViewedAt;
    }
}
