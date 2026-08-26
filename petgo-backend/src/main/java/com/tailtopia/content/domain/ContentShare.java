package com.tailtopia.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 单条内容对外分享行（Story 9.3 · FR-73，{@code content_shares}）。
 *
 * <p>沿用名片 / 里程碑分享范式：对外只用不可枚举 {@link #shareToken}（绝不外露顺序 id），
 * 公开 H5 {@code GET /c/{shareToken}} 据此直出。
 *
 * <p>🔴 <b>与名片分享是不同的链接类型，落地页也不同</b>（AD-15 Rule 5）。名片链接落的是
 * 整本档案的只读视图；单条分享的落地页<b>只有被分享的那一条</b>，没有任何通往该宠物其它内容的路径。
 * 复用同一落点等于把「我只想分享一条」变成「我把整本都给你了」。
 *
 * <p>{@code content_post_id} 唯一 ⇒ 同一条内容重复分享<b>复用同一 token</b>（幂等）。
 * 本行不存内容快照：文案/图片一律读时从 {@code content_posts} 取，
 * 这样作者改了内容或删了内容，分享链接的表现立刻跟上（删除 → 失效页）。
 */
@Entity
@Table(name = "content_shares")
public class ContentShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_token", nullable = false, length = 64, updatable = false)
    private String shareToken;

    @Column(name = "content_post_id", nullable = false, updatable = false)
    private Long contentPostId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentShare() {
    }

    public static ContentShare create(String shareToken, long contentPostId) {
        ContentShare s = new ContentShare();
        s.shareToken = shareToken;
        s.contentPostId = contentPostId;
        return s;
    }

    /** 重复分享：token 不变，仅刷新 updated_at（用于"最近分享过"这类运营口径）。 */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getShareToken() {
        return shareToken;
    }

    public Long getContentPostId() {
        return contentPostId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
