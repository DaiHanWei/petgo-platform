package com.tailtopia.content.domain;

import com.tailtopia.shared.schedule.ScheduleWindow;
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
 * 顶置坑位排期（V1.1.6 Story 4.1 · FR-68）。
 *
 * <h2>🛡 没有状态列，这是刻意的</h2>
 * 「生效中」一律**查询时**按 {@code [startsAt, endsAt)} 判定（AD-9）：不落状态列、不建定时扫描器。
 * 想知道当前处于哪一段，走 {@link ScheduleWindow#phaseAt}。
 *
 * <h2>🛡 坑位是一个字符串字段，不是枚举</h2>
 * AD-8 要求「新增坑位时只需增加坑位记录、不需重构」。做成 Java 枚举就意味着新增坑位要改代码，
 * 与该要求直接冲突。本版本只有 {@link #SLOT_HOME_FEED} 一个取值，下游 V1.2.0 的话题页坑位
 * 直接写一个新取值即可。
 */
@Entity
@Table(name = "content_pins")
public class ContentPin {

    /** 本版本唯一的坑位：首页 Feed 顶部。 */
    public static final String SLOT_HOME_FEED = "HOME_FEED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot", nullable = false, length = 32)
    private String slot;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 16)
    private PinObjectType objectType;

    /** 仅 {@link PinObjectType#CONTENT} 有值。 */
    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "promo_image_url", length = 512)
    private String promoImageUrl;

    @Column(name = "promo_title", length = 120)
    private String promoTitle;

    /** 外部链接或 App 内深链，二选一由运营填；可空 = 纯展示卡。 */
    @Column(name = "promo_link_url", length = 512)
    private String promoLinkUrl;

    /** UTC 绝对时刻。运营配的是 WIB 墙上时间，**入库前**已换算。 */
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /**
     * 提前结束时刻（顶置内容被下架时写入）。
     *
     * <p>🔴 **不覆盖 {@link #endsAt}** —— 覆盖掉之后运营只会看到「这条 14:32 结束了」，
     * 无从知道是排期到点还是被下架带走的。写入时保证不晚于 {@link #endsAt}（DB 亦有约束），
     * 因此 SQL 侧可直接用 {@code COALESCE(terminated_at, ends_at)} 当生效结束时刻。
     */
    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentPin() {
    }

    /** (a) 顶置一篇已发布内容。 */
    public static ContentPin ofContent(String slot, long contentId, Instant startsAt, Instant endsAt) {
        ContentPin p = base(slot, startsAt, endsAt);
        p.objectType = PinObjectType.CONTENT;
        p.contentId = contentId;
        return p;
    }

    /** (b) 推广卡片。[linkUrl] 可空（纯展示卡）。 */
    public static ContentPin ofPromo(String slot, String imageUrl, String title, String linkUrl,
            Instant startsAt, Instant endsAt) {
        ContentPin p = base(slot, startsAt, endsAt);
        p.objectType = PinObjectType.PROMO;
        p.promoImageUrl = imageUrl;
        p.promoTitle = title;
        p.promoLinkUrl = linkUrl;
        return p;
    }

    private static ContentPin base(String slot, Instant startsAt, Instant endsAt) {
        ContentPin p = new ContentPin();
        p.slot = slot;
        p.startsAt = startsAt;
        p.endsAt = endsAt;
        return p;
    }

    /** 生效意义上的结束时刻（含提前结束）。 */
    public Instant effectiveEnd() {
        return ScheduleWindow.effectiveEnd(endsAt, terminatedAt);
    }

    public boolean isActiveAt(Instant now) {
        return ScheduleWindow.isActiveAt(now, startsAt, effectiveEnd());
    }

    /**
     * 提前结束（幂等）：已结束过的不再改时刻；[at] 不得晚于排期结束时刻。
     *
     * @return 本次是否真的改动了
     */
    public boolean terminateAt(Instant at) {
        if (terminatedAt != null || !at.isBefore(endsAt)) {
            return false;
        }
        this.terminatedAt = at;
        return true;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSlot() {
        return slot;
    }

    public PinObjectType getObjectType() {
        return objectType;
    }

    public Long getContentId() {
        return contentId;
    }

    public String getPromoImageUrl() {
        return promoImageUrl;
    }

    public String getPromoTitle() {
        return promoTitle;
    }

    public String getPromoLinkUrl() {
        return promoLinkUrl;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Instant getTerminatedAt() {
        return terminatedAt;
    }
}
