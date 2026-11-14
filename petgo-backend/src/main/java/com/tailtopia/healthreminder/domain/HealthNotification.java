package com.tailtopia.healthreminder.domain;

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
import java.time.LocalDate;

/**
 * 一条健康通知（{@code health_notifications}，V1.5.0 Story 2.1，架构增量 §1.3）：一次推送 + 一条通知中心条目，可承载多条提醒。
 * {@code public_token} 不可枚举，= {@code notifications.target_ref} = 埋点 {@code notification_id}。
 */
@Entity
@Table(name = "health_notifications")
public class HealthNotification {

    /** 推送结果（架构 Z-7）。 */
    public enum PushResult {
        SENT,
        MUTED,
        NO_TOKEN,
        FAILED
    }

    /** 发出时决定的落点：合并 → PAGE；否则按 kind（FORM_DATE_EMPTY 归 FORM）。安全情形点开时再判。 */
    public enum Route {
        FORM,
        PAGE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, unique = true)
    private String publicToken;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_kind", nullable = false, length = 32)
    private ReminderKind notificationKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_point", nullable = false, length = 16)
    private PushPoint pushPoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private ReminderOrigin origin;

    @Column(name = "merged_count", nullable = false)
    private Integer mergedCount;

    @Column(name = "deferred_days", nullable = false)
    private Integer deferredDays = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 8)
    private Route route;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_result", nullable = false, length = 16)
    private PushResult pushResult;

    @Column(name = "inbox_notification_id")
    private Long inboxNotificationId;

    @Column(name = "send_date", nullable = false)
    private LocalDate sendDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HealthNotification() {
    }

    public static HealthNotification create(String publicToken, long userId, long petProfileId, ReminderKind kind,
            PushPoint point, ReminderOrigin origin, int mergedCount, int deferredDays, Route route,
            LocalDate sendDate) {
        HealthNotification n = new HealthNotification();
        n.publicToken = publicToken;
        n.userId = userId;
        n.petProfileId = petProfileId;
        n.notificationKind = kind;
        n.pushPoint = point;
        n.origin = origin;
        n.mergedCount = mergedCount;
        n.deferredDays = deferredDays;
        n.route = route;
        n.sendDate = sendDate;
        // 落库时还没推；投递后回写真实结果。先置 FAILED，崩在中途也不会误记成已送达。
        n.pushResult = PushResult.FAILED;
        return n;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void recordDelivery(PushResult result, Long inboxNotificationId) {
        this.pushResult = result;
        this.inboxNotificationId = inboxNotificationId;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public ReminderKind getNotificationKind() {
        return notificationKind;
    }

    public PushPoint getPushPoint() {
        return pushPoint;
    }

    public ReminderOrigin getOrigin() {
        return origin;
    }

    public int getMergedCount() {
        return mergedCount;
    }

    public int getDeferredDays() {
        return deferredDays;
    }

    public Route getRoute() {
        return route;
    }

    public PushResult getPushResult() {
        return pushResult;
    }

    public Long getInboxNotificationId() {
        return inboxNotificationId;
    }

    public LocalDate getSendDate() {
        return sendDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
