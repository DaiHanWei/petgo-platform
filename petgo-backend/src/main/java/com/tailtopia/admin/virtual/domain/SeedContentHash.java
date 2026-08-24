package com.tailtopia.admin.virtual.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 种子内容去重锚（Story 9.8 Part 2）。content_hash 主键 → 跨批防重发。 */
@Entity
@Table(name = "seed_content_hashes")
public class SeedContentHash {

    @Id
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "post_id", nullable = false)
    private long postId;

    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 实际按下发布的**后台账号**（V1.1.6 Story 12.1 · AC7 最后一条）。
     *
     * <p>🔴 这是运营真实账号与虚拟账号最大的差别：虚拟账号是平台身份，真实账号是
     * <b>某个真人的账号</b> —— 出事要能追到具体是哪个后台账号操作的。
     * 存量行为 {@code null}（那时还没有这个概念，<b>不回填假数据</b>）。
     */
    @Column(name = "published_by_admin_id")
    private Long publishedByAdminId;

    protected SeedContentHash() {
    }

    public static SeedContentHash of(String contentHash, long postId, long authorId,
            Long publishedByAdminId) {
        SeedContentHash h = new SeedContentHash();
        h.contentHash = contentHash;
        h.postId = postId;
        h.authorId = authorId;
        h.publishedByAdminId = publishedByAdminId;
        h.createdAt = Instant.now();
        return h;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getPostId() {
        return postId;
    }

    public long getAuthorId() {
        return authorId;
    }

    public Long getPublishedByAdminId() {
        return publishedByAdminId;
    }
}
