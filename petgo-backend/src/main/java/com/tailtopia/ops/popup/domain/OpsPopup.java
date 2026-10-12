package com.tailtopia.ops.popup.domain;

import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetType;
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
 * 运营弹窗（V1.3.4 FR-137 · AD-14 · D-23 / D-24）。
 *
 * <p>本主题只读：配置由 admin 主题（AB-30）写入（D-2 / AD-1）。{@link #create} / {@link #activate()} /
 * {@link #disable()} 供 admin 主题与测试夹具使用，<b>本主题不提供写接口</b>。
 *
 * <p>🔴 对外标识一律 {@code publicToken}（D-24），自增 id 不外露。
 * 🔴 频次挂在 {@code id} 上（{@code ops_popup_impressions.popup_id}）：编辑不换 id、不换 token，
 * 频次不重置（AD-14）。
 *
 * <p>{@code imageUrlId / imageUrlEn} 存「https:// 完整地址」或「公开桶 OSS objectKey」二者之一，
 * 读接口统一换算（见 {@code OpsPopupCandidateService}）。
 */
@Entity
@Table(name = "ops_popups")
public class OpsPopup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, unique = true, updatable = false)
    private String publicToken;

    /** 仅后台可见，不下发 App。 */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OpsPopupStatus status;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** null = 长期有效（D-23）。 */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 8)
    private OpsPopupAudience audience;

    @Column(name = "new_user_days", nullable = false)
    private int newUserDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 8)
    private OpsPopupFrequency frequency;

    @Column(name = "image_url_id", nullable = false, length = 512)
    private String imageUrlId;

    @Column(name = "image_url_en", length = 512)
    private String imageUrlEn;

    @Column(name = "title_id", length = 120)
    private String titleId;

    @Column(name = "title_en", length = 120)
    private String titleEn;

    @Column(name = "body_id", length = 255)
    private String bodyId;

    @Column(name = "body_en", length = 255)
    private String bodyEn;

    @Column(name = "button_text_id", nullable = false, length = 40)
    private String buttonTextId;

    @Column(name = "button_text_en", length = 40)
    private String buttonTextEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private OpsTargetType targetType;

    @Column(name = "target_value", length = 512)
    private String targetValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OpsPopup() {
    }

    /**
     * 新建弹窗。<b>默认 {@code DISABLED}</b>（与商品 / banner 同一安全默认：新建即生效会在运营还没检查图时
     * 推给所有人），自动生成 {@code publicToken}。目标合法性由调用方先经 {@code OpsTargetPolicy} 校验；
     * 库层 CHECK 兜底 {@code (target_type = 'NONE') = (target_value IS NULL)}。
     */
    public static OpsPopup create(String name, Instant startsAt, Instant endsAt, int priority,
            OpsPopupAudience audience, int newUserDays, OpsPopupFrequency frequency,
            String imageUrlId, String imageUrlEn, String titleId, String titleEn,
            String bodyId, String bodyEn, String buttonTextId, String buttonTextEn,
            OpsTargetType targetType, String targetValue) {
        OpsPopup p = new OpsPopup();
        p.publicToken = OpsTokenGenerator.next();
        p.status = OpsPopupStatus.DISABLED;
        p.name = name;
        p.startsAt = startsAt;
        p.endsAt = endsAt;
        p.priority = priority;
        p.audience = audience;
        p.newUserDays = newUserDays;
        p.frequency = frequency;
        p.imageUrlId = imageUrlId;
        p.imageUrlEn = imageUrlEn;
        p.titleId = titleId;
        p.titleEn = titleEn;
        p.bodyId = bodyId;
        p.bodyEn = bodyEn;
        p.buttonTextId = buttonTextId;
        p.buttonTextEn = buttonTextEn;
        p.targetType = targetType;
        p.targetValue = targetValue;
        return p;
    }

    public void activate() {
        this.status = OpsPopupStatus.ACTIVE;
    }

    public void disable() {
        this.status = OpsPopupStatus.DISABLED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public String getName() {
        return name;
    }

    public OpsPopupStatus getStatus() {
        return status;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public int getPriority() {
        return priority;
    }

    public OpsPopupAudience getAudience() {
        return audience;
    }

    public int getNewUserDays() {
        return newUserDays;
    }

    public OpsPopupFrequency getFrequency() {
        return frequency;
    }

    public String getImageUrlId() {
        return imageUrlId;
    }

    public String getImageUrlEn() {
        return imageUrlEn;
    }

    public String getTitleId() {
        return titleId;
    }

    public String getTitleEn() {
        return titleEn;
    }

    public String getBodyId() {
        return bodyId;
    }

    public String getBodyEn() {
        return bodyEn;
    }

    public String getButtonTextId() {
        return buttonTextId;
    }

    public String getButtonTextEn() {
        return buttonTextEn;
    }

    public OpsTargetType getTargetType() {
        return targetType;
    }

    public String getTargetValue() {
        return targetValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
