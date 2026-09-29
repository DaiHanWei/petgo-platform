package com.tailtopia.content.autocomment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 自动评论留档（{@code auto_comment_logs}）。唯一约束 {@code post_id} 是「每帖至多自动评论一次」的单一事实源；
 * 只有 {@link AutoCommentStatus#FAILED} 的行会在下一轮被重试（同一行上 {@code attempts+1}）。
 */
@Entity
@Table(name = "auto_comment_logs")
public class AutoCommentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AutoCommentStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "virtual_user_id")
    private Long virtualUserId;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "prompt_version", length = 16)
    private String promptVersion;

    @Column(name = "generated_text", length = 400)
    private String generatedText;

    @Column(name = "skip_reason", length = 200)
    private String skipReason;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutoCommentLog() {
    }

    public static AutoCommentLog forPost(long postId) {
        AutoCommentLog log = new AutoCommentLog();
        log.postId = postId;
        log.attempts = 0;
        return log;
    }

    /** 开始一次新尝试：计数 +1，清掉上一次失败留下的结果字段。 */
    public void beginAttempt() {
        attempts++;
        virtualUserId = null;
        commentId = null;
        generatedText = null;
        skipReason = null;
        errorCode = null;
        latencyMs = null;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public AutoCommentStatus getStatus() {
        return status;
    }

    public void setStatus(AutoCommentStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Long getVirtualUserId() {
        return virtualUserId;
    }

    public void setVirtualUserId(Long virtualUserId) {
        this.virtualUserId = virtualUserId;
    }

    public Long getCommentId() {
        return commentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getGeneratedText() {
        return generatedText;
    }

    public void setGeneratedText(String generatedText) {
        this.generatedText = generatedText;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
