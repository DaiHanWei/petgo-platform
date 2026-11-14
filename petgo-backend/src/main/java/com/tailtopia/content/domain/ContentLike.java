package com.tailtopia.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 内容点赞关系（Story 3.4）。用户×内容唯一（{@code uq_content_likes_post_user}）防重复点赞。
 */
@Entity
@Table(name = "content_likes")
public class ContentLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 是否由自动点赞任务创建（V1.5.0 likes FR-218）。只有自动点赞任务写 {@code true}（走
     * {@code ContentLikeRepository#insertAutomatedIfAbsent} 原生 INSERT）；App / 后台 / 运营点赞一律 {@code false}。
     * 排序 / 推荐 / 热度 / 运营统计排除；展示赞数、点赞人、主页 Suka 不排除。
     */
    @Column(name = "is_automated", nullable = false)
    private boolean automated;

    protected ContentLike() {
    }

    public static ContentLike of(long postId, long userId) {
        ContentLike l = new ContentLike();
        l.postId = postId;
        l.userId = userId;
        return l;
    }

    /** 仅供测试造数：一条自动赞。生产写入走 {@code ContentLikeRepository#insertAutomatedIfAbsent}。 */
    public static ContentLike automated(long postId, long userId) {
        ContentLike l = of(postId, userId);
        l.automated = true;
        return l;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isAutomated() {
        return automated;
    }
}
