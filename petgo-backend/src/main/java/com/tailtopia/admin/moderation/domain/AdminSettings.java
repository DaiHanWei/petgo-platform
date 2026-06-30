package com.tailtopia.admin.moderation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 单行系统配置（Story 4.3，AB-3C）。固定主键 {@code id=1}（DB CHECK 约束保证单行）。
 * V1.0.0 种子 {@code manualReviewEnabled=false} ＝ 维持现网行为；超管可切换。
 */
@Entity
@Table(name = "admin_settings")
public class AdminSettings {

    /** 固定单行主键。 */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "manual_review_enabled", nullable = false)
    private boolean manualReviewEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminSettings() {
    }

    public boolean isManualReviewEnabled() {
        return manualReviewEnabled;
    }

    public void setManualReviewEnabled(boolean enabled) {
        this.manualReviewEnabled = enabled;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }
}
