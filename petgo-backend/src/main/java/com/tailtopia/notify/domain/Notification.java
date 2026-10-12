package com.tailtopia.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 通知/推送记录（Story 6.1 建 {@code notifications} 表）。统一推送出口每发一条写一行，供 6.6 通知中心读取。
 *
 * <p>对外只暴露 {@code deepLinkToken}（不可枚举），{@code targetRef} 仅内部回查；
 * 标题/正文绝不含健康数据明文。时间戳 UTC。
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private NotificationType type;

    @Column(name = "title", length = 120)
    private String title;

    @Column(name = "body", length = 255)
    private String body;

    @Column(name = "deep_link_type", length = 32)
    private String deepLinkType;

    @Column(name = "deep_link_token", length = 64)
    private String deepLinkToken;

    @Column(name = "target_ref", length = 64)
    private String targetRef;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    /**
     * 合并键（V1.3.4 follow · Story 3.1 · AD-9）：{@code LIKE:POST:{postId}} / {@code COMMENT:PLACE:{placeId}}；
     * 未读期间同键只留一行（部分唯一索引 {@code uq_notifications_unread_aggregate}）。不聚合的类型为 null。
     */
    @Column(name = "aggregate_key", length = 64)
    private String aggregateKey;

    /** 仅 {@code CONTENT_COMMENTED}：指向 {@code comments.id}，列表据此现查评论原文（AD-3）。 */
    @Column(name = "comment_id")
    private Long commentId;

    /** {@link #VARIANT_REPLY} = 回复了你的评论；其余 null。 */
    @Column(name = "variant", length = 16)
    private String variant;

    /** 合并刷新时刻；新建时等于 {@link #createdAt}。合并刷新走原生 SQL（{@code createdAt} 不可经实体更新）。 */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** {@code variant} 取值：回复了你的评论（Story 3.1）。DB 无 CHECK，取值只在这里收口。 */
    public static final String VARIANT_REPLY = "REPLY";

    protected Notification() {
    }

    public static Notification of(long recipientUserId, NotificationType type, String title, String body,
            String deepLinkType, String deepLinkToken, String targetRef) {
        Notification n = new Notification();
        n.recipientUserId = recipientUserId;
        n.type = type;
        n.title = title;
        n.body = body;
        n.deepLinkType = deepLinkType;
        n.deepLinkToken = deepLinkToken;
        n.targetRef = targetRef;
        return n;
    }

    /**
     * 互动通知（V1.3.4 follow · Story 3.1）：带合并键 / 评论 id / variant。触发人另写 {@code notification_actors}。
     * {@link #of} 的行为不变（新列为 null）。
     */
    public static Notification interaction(long recipientUserId, NotificationType type, String title,
            String body, String deepLinkType, String deepLinkToken, String targetRef, String aggregateKey,
            Long commentId, String variant) {
        Notification n = of(recipientUserId, type, title, body, deepLinkType, deepLinkToken, targetRef);
        n.aggregateKey = aggregateKey;
        n.commentId = commentId;
        n.variant = variant;
        return n;
    }

    public void markRead() {
        if (!read) {
            this.read = true;
            this.readAt = Instant.now();
        }
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getDeepLinkType() {
        return deepLinkType;
    }

    public String getDeepLinkToken() {
        return deepLinkToken;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public String getAggregateKey() {
        return aggregateKey;
    }

    public Long getCommentId() {
        return commentId;
    }

    public String getVariant() {
        return variant;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
