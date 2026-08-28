package com.tailtopia.content.larksync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Lark 定时发帖状态机（spec-lark-scheduled-posts）。content_code 唯一 → 全链路去重锚：
 * 「发布成功但表格回写失败」的崩溃窗口，下轮命中 PUBLISHED 记录只补回写、绝不双发。
 */
@Entity
@Table(name = "lark_content_publishes")
public class LarkContentPublish {

    public enum Status {
        PUBLISHED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_code", nullable = false, length = 32, unique = true)
    private String contentCode;

    @Column(name = "image_codes", length = 255)
    private String imageCodes;

    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(name = "post_id")
    private Long postId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LarkContentPublish() {
    }

    public static LarkContentPublish published(String contentCode, String imageCodes,
            long authorId, long postId) {
        LarkContentPublish r = base(contentCode, imageCodes, authorId);
        r.postId = postId;
        r.status = Status.PUBLISHED;
        return r;
    }

    public static LarkContentPublish failed(String contentCode, String imageCodes,
            long authorId, String failReason) {
        LarkContentPublish r = base(contentCode, imageCodes, authorId);
        r.status = Status.FAILED;
        r.failReason = truncate(failReason);
        return r;
    }

    private static LarkContentPublish base(String contentCode, String imageCodes, long authorId) {
        LarkContentPublish r = new LarkContentPublish();
        r.contentCode = contentCode;
        r.imageCodes = imageCodes;
        r.authorId = authorId;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        return r;
    }

    /** FAILED → PUBLISHED（运营清空状态格重试成功后翻面）。 */
    public void markPublished(long authorId, long postId) {
        this.authorId = authorId;
        this.postId = postId;
        this.status = Status.PUBLISHED;
        this.failReason = null;
        this.updatedAt = Instant.now();
    }

    /** 重试仍失败：刷新原因与时间。 */
    public void markFailed(String failReason) {
        this.status = Status.FAILED;
        this.failReason = truncate(failReason);
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 255 ? s.substring(0, 255) : s;
    }

    public Long getId() {
        return id;
    }

    public String getContentCode() {
        return contentCode;
    }

    public long getAuthorId() {
        return authorId;
    }

    public Long getPostId() {
        return postId;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailReason() {
        return failReason;
    }
}
