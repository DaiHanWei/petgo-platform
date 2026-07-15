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

    protected SeedContentHash() {
    }

    public static SeedContentHash of(String contentHash, long postId, long authorId) {
        SeedContentHash h = new SeedContentHash();
        h.contentHash = contentHash;
        h.postId = postId;
        h.authorId = authorId;
        h.createdAt = Instant.now();
        return h;
    }

    public String getContentHash() {
        return contentHash;
    }
}
