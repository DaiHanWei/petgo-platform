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

    public void markRead() {
        if (!read) {
            this.read = true;
            this.readAt = Instant.now();
        }
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
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
}
