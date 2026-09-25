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
 * 评论点赞关系（V1.3.0 批次 A · Story 2.4 · AD-A7）。
 *
 * <p>用户×评论唯一（{@code uq_comment_likes_comment_user}）防重复点赞 —— 幂等靠这条约束兜底，
 * 不靠先查后插（那有并发窗口）。
 *
 * <p>🔴 <b>逐字对齐 {@link ContentLike}</b>：同一件事在库里长同一个样子。
 * 一级、二级评论**共用本表**，层级由 {@code comments.parent_id} 决定，与点赞无关。
 *
 * <p>⚠️ <b>没有 likeCount 字段，也不要加</b>：点赞数一律实时聚合（AD-A7.3）。
 * 全库至今没有任何冗余计数列，开这个头就要处理并发增减、回填与对账。
 */
@Entity
@Table(name = "comment_likes")
public class CommentLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommentLike() {
    }

    public static CommentLike of(long commentId, long userId) {
        CommentLike l = new CommentLike();
        l.commentId = commentId;
        l.userId = userId;
        return l;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCommentId() {
        return commentId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
