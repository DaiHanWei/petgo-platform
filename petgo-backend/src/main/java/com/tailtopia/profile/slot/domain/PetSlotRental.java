package com.tailtopia.profile.slot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * 月租名额（V1.4.0 multi-pet Story 2.2 · AD-5 · 表 {@code pet_slot_rentals}）：每个名额一行。
 *
 * <p>名额数只经 {@code PetSlotService.quotaOf} 计算（只数 {@code ACTIVE AND expires_at > now}），本实体不参与计数口径。
 * 对外只露 {@link #publicToken}（不可枚举），不露 {@link #id}。
 */
@Entity(name = "PetSlotRental")
@Table(name = "pet_slot_rentals")
public class PetSlotRental {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private PetSlotRentalSource source;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PetSlotRentalStatus status;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "purchase_id", nullable = false, updatable = false)
    private Long purchaseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PetSlotRental() {
    }

    /** 新建一个生效中的名额：{@code starts_at = startsAt}、{@code expires_at = startsAt + days}。 */
    public static PetSlotRental start(String publicToken, long userId, PetSlotRentalSource source, Instant startsAt,
            int days, long purchaseId) {
        PetSlotRental r = new PetSlotRental();
        r.publicToken = publicToken;
        r.userId = userId;
        r.source = source;
        r.startsAt = startsAt;
        r.expiresAt = startsAt.plus(Duration.ofDays(days));
        r.status = PetSlotRentalStatus.ACTIVE;
        r.purchaseId = purchaseId;
        r.createdAt = startsAt;
        return r;
    }

    /** 生效中：{@code ACTIVE} 且 {@code expires_at > now}（与 {@code quotaOf} 同口径）。 */
    public boolean isLiveAt(Instant now) {
        return status == PetSlotRentalStatus.ACTIVE && expiresAt.isAfter(now);
    }

    /** 续租：在原到期日上延长 {@code days} 天，并清空 {@code reminded_at}（让 Story 2.3 对新到期日再提醒一次）。 */
    public void extend(int days) {
        this.expiresAt = this.expiresAt.plus(Duration.ofDays(days));
        this.remindedAt = null;
    }

    /** 到期（Story 2.3 扫描）：{@code status = EXPIRED}、{@code expired_at = now}。 */
    public void markExpired(Instant now) {
        this.status = PetSlotRentalStatus.EXPIRED;
        this.expiredAt = now;
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

    public PetSlotRentalSource getSource() {
        return source;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public PetSlotRentalStatus getStatus() {
        return status;
    }

    public Instant getRemindedAt() {
        return remindedAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public Long getPurchaseId() {
        return purchaseId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
