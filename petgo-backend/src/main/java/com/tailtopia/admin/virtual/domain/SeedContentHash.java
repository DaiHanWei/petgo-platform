package com.tailtopia.admin.virtual.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 种子内容去重指纹（Story 9.8 Part 2；V1.1.6 Story 13.4 改键）。
 *
 * <h2>🔴 主键是 {@code (content_hash, author_id)}，不是 content_hash 单列</h2>
 * 原先单列主键有两个实际后果：
 * <ul>
 *   <li>同一文案想用两个不同账号各发一遍 —— <b>内容运营的常规操作</b>，
 *       引入运营真实账号后会更频繁 —— <b>第二次会被静默吞掉</b>。</li>
 *   <li>没有任何清理逻辑：已发布内容被删除后指纹仍在，
 *       <b>同样的文案永久无法重发</b>。</li>
 * </ul>
 *
 * <p>🛡 清理挂在 {@code SeedHashCleanupListener} 上，订阅"内容不再可展示"事件 ——
 * <b>不是逐个删除入口各加一行</b>：那样漏一条就是那条路径删掉的内容对应的文案永久无法重发。
 */
@Entity
@Table(name = "seed_content_hashes")
@jakarta.persistence.IdClass(SeedContentHash.Key.class)
public class SeedContentHash {

    /**
     * 复合主键。
     *
     * <p>⚠️ 必须可序列化且有 equals/hashCode（JPA 要求）。用 record 最省事，
     * 但它得有一个无参构造给 Hibernate 用 —— 所以还是写成普通类。
     */
    public static class Key implements java.io.Serializable {
        private String contentHash;
        private long authorId;

        public Key() {
        }

        public Key(String contentHash, long authorId) {
            this.contentHash = contentHash;
            this.authorId = authorId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key k)) {
                return false;
            }
            return authorId == k.authorId && java.util.Objects.equals(contentHash, k.contentHash);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(contentHash, authorId);
        }
    }

    @Id
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Id
    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(name = "post_id", nullable = false)
    private long postId;

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
