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
import org.hibernate.annotations.DynamicUpdate;

/**
 * 运营弹窗（V1.3.4 FR-137 · AD-14 · D-23 / D-24）。
 *
 * <p>本主题只读：配置由 admin 主题（AB-30）写入（D-2 / AD-1）。{@link #create} / {@link #update} /
 * {@link #activate()} / {@link #disable()} 供 admin 主题与测试夹具使用，<b>本主题不提供写接口</b>。
 *
 * <p>🔴 对外标识一律 {@code publicToken}（D-24），自增 id 不外露。
 * 🔴 频次挂在 {@code id} 上（{@code ops_popup_impressions.popup_id}）：编辑不换 id、不换 token，
 * 频次不重置（AD-14）。
 *
 * <p>{@code imageUrlId / imageUrlEn} 存「https:// 完整地址」或「公开桶 OSS objectKey」二者之一，
 * 读接口统一换算（见 {@code OpsPopupCandidateService}）。
 *
 * <p>🔴 {@code @DynamicUpdate}：UPDATE 只写脏列。编辑（{@link #update}）不碰 {@code status}，于是编辑事务
 * 不会把装载时的旧状态写回 —— 运营 A 在抽屉里改文案、B 同时点了启用 / 停用，A 保存后 B 的启停仍在。
 * （其余字段两人同时编辑仍是后写赢，暂不加 {@code @Version}。）
 */
@Entity
@DynamicUpdate
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

    /** V1.4.1 四维人群条件 JSON（{@link com.tailtopia.ops.audience.AudienceFilters}）；null = 不筛。 */
    @Column(name = "audience_filter", length = 512)
    private String audienceFilter;

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

    /**
     * 后台编辑（AB-30）：字段同 {@link #create}。
     *
     * <p>🔴 <b>不动 {@code id} / {@code publicToken} / {@code status}</b>：频次记录挂在 id 上（AD-14），
     * 换 id 等于让已看过的人再看一遍；token 是埋点 {@code popup_id}（D-24），换了数据就断成两截；
     * 启停另走 {@link #activate()} / {@link #disable()}，编辑不顺手改状态（改文案不该让一条停用的弹窗上线）。
     */
    public void update(String name, Instant startsAt, Instant endsAt, int priority,
            OpsPopupAudience audience, int newUserDays, OpsPopupFrequency frequency,
            String imageUrlId, String imageUrlEn, String titleId, String titleEn,
            String bodyId, String bodyEn, String buttonTextId, String buttonTextEn,
            OpsTargetType targetType, String targetValue) {
        this.name = name;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.priority = priority;
        this.audience = audience;
        this.newUserDays = newUserDays;
        this.frequency = frequency;
        this.imageUrlId = imageUrlId;
        this.imageUrlEn = imageUrlEn;
        this.titleId = titleId;
        this.titleEn = titleEn;
        this.bodyId = bodyId;
        this.bodyEn = bodyEn;
        this.buttonTextId = buttonTextId;
        this.buttonTextEn = buttonTextEn;
        this.targetType = targetType;
        this.targetValue = targetValue;
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

    /** 四维人群条件（V1.4.1）；未设 = {@link com.tailtopia.ops.audience.AudienceFilters#none()}。 */
    public com.tailtopia.ops.audience.AudienceFilters getAudienceFilters() {
        return com.tailtopia.ops.audience.AudienceFilters.parse(audienceFilter);
    }

    /** 新建 / 编辑时写入（全不筛 → 列存 NULL）。 */
    public void setAudienceFilters(com.tailtopia.ops.audience.AudienceFilters filters) {
        this.audienceFilter = filters == null ? null : filters.toJsonOrNull();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
