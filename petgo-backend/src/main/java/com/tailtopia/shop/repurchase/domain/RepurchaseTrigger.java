package com.tailtopia.shop.repurchase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** 复购触发记录（Story 6.3，FR-109）。日扫落库，首页与推送读同一份（AD-12）。 */
@Entity
@Table(name = "repurchase_triggers")
public class RepurchaseTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "source_order_id")
    private Long sourceOrderId;

    @Column(name = "estimated_depletion_date", nullable = false)
    private LocalDate estimatedDepletionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private RepurchaseTriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RepurchaseTriggerStatus status;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RepurchaseTrigger() {
    }

    public static RepurchaseTrigger foodLow(long userId, long petProfileId, long skuId,
            Long sourceOrderId, LocalDate depletionDate) {
        RepurchaseTrigger t = new RepurchaseTrigger();
        t.userId = userId;
        t.petProfileId = petProfileId;
        t.skuId = skuId;
        t.sourceOrderId = sourceOrderId;
        t.estimatedDepletionDate = depletionDate;
        t.triggerType = RepurchaseTriggerType.FOOD_LOW;
        t.status = RepurchaseTriggerStatus.ACTIVE;
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        return t;
    }

    /** 🔴 用户再次购买该 SKU → 旧触发立即失效，按新订单重新起算。 */
    public void supersede() {
        this.status = RepurchaseTriggerStatus.SUPERSEDED;
        this.updatedAt = Instant.now();
    }

    public void dismiss() {
        this.status = RepurchaseTriggerStatus.DISMISSED;
        this.dismissedAt = Instant.now();
        this.updatedAt = this.dismissedAt;
    }

    public void markConverted() {
        this.status = RepurchaseTriggerStatus.CONVERTED;
        this.updatedAt = Instant.now();
    }

    /** 「推送一次」的落点：推过就记时刻，日扫重算<b>不重推</b>。 */
    public void markNotified() {
        this.notifiedAt = Instant.now();
        this.updatedAt = this.notifiedAt;
    }

    public boolean isNotified() {
        return notifiedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public Long getSourceOrderId() {
        return sourceOrderId;
    }

    public LocalDate getEstimatedDepletionDate() {
        return estimatedDepletionDate;
    }

    public RepurchaseTriggerType getTriggerType() {
        return triggerType;
    }

    public RepurchaseTriggerStatus getStatus() {
        return status;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
