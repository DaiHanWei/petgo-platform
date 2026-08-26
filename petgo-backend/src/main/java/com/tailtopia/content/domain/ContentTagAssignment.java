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
 * 内容装饰标签分配（V1.1.6 Story 5.2 · FR-75）。
 *
 * <p>🛡 **没有状态列**：生效与否查询时按 {@code [startsAt, endsAt)} 判定；
 * {@code endsAt} 为空 = 永久。
 *
 * <p>⚠️ 打标是**流量动作**：生效中时该内容在推荐排序上有 ×1.3 加权。
 * 因为加权由同一份判定推导，"到期 → 加成一并消失"自动成立。
 */
@Entity
@Table(name = "content_tag_assignments")
public class ContentTagAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContentTagAssignment() {
    }

    public static ContentTagAssignment of(long postId, long tagId, Instant startsAt, Instant endsAt) {
        ContentTagAssignment a = new ContentTagAssignment();
        a.postId = postId;
        a.tagId = tagId;
        a.startsAt = startsAt;
        a.endsAt = endsAt;
        return a;
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

    public Long getTagId() {
        return tagId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }
}
