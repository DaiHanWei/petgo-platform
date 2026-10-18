package com.tailtopia.gift.domain;

import com.tailtopia.profile.domain.PetType;
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
 * 礼物目录行（V1.4.0 gift Story 1.1 · AD-1 · 表 {@code gift_catalog}）。只下线不物理删除。
 *
 * <p>本 story 只读（App 目录 / 发起送礼）；后台录入与改值在 Story 3.1（届时再加写方法）。
 * {@link #forCat} / {@link #forDog} / {@link #forOther} 三者皆 false = 通用礼物。
 */
@Entity(name = "GiftCatalogItem")
@Table(name = "gift_catalog")
public class GiftCatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "name_en", nullable = false, length = 40)
    private String nameEn;

    @Column(name = "name_id", nullable = false, length = 40)
    private String nameId;

    @Column(name = "icon_url", nullable = false, length = 1024)
    private String iconUrl;

    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "for_cat", nullable = false)
    private boolean forCat;

    @Column(name = "for_dog", nullable = false)
    private boolean forDog;

    @Column(name = "for_other", nullable = false)
    private boolean forOther;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private GiftKind kind;

    @Column(name = "min_level")
    private Integer minLevel;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GiftCatalogStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected GiftCatalogItem() {
    }

    /**
     * 新建目录行（Story 3.1 后台录入与测试夹具用）。{@code kind = NORMAL} 时 {@code minLevel} 必须为 null，
     * {@code EXCLUSIVE} 时必须 ≥1 —— 与库级 CHECK 同口径，这里先挡一道。
     */
    public static GiftCatalogItem create(String publicToken, String nameEn, String nameId, String iconUrl, long price,
            boolean forCat, boolean forDog, boolean forOther, GiftKind kind, Integer minLevel, int sortOrder) {
        if ((kind == GiftKind.EXCLUSIVE) != (minLevel != null) || (minLevel != null && minLevel < 1)) {
            throw new IllegalArgumentException("专属礼物必须带 ≥1 的解锁等级，普通礼物不得带等级");
        }
        GiftCatalogItem g = new GiftCatalogItem();
        g.publicToken = publicToken;
        g.nameEn = nameEn;
        g.nameId = nameId;
        g.iconUrl = iconUrl;
        g.price = price;
        g.forCat = forCat;
        g.forDog = forDog;
        g.forOther = forOther;
        g.kind = kind;
        g.minLevel = minLevel;
        g.sortOrder = sortOrder;
        g.status = GiftCatalogStatus.ONLINE;
        return g;
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

    /** 通用礼物：三个物种标记皆 false。 */
    public boolean isUniversal() {
        return !forCat && !forDog && !forOther;
    }

    /** 是否标了适用该物种（{@code null} 物种一律 false）。 */
    public boolean suits(PetType petType) {
        if (petType == null) {
            return false;
        }
        return switch (petType) {
            case CAT -> forCat;
            case DOG -> forDog;
            case OTHER -> forOther;
        };
    }

    public boolean isOnline() {
        return status == GiftCatalogStatus.ONLINE;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getNameId() {
        return nameId;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public long getPrice() {
        return price;
    }

    public boolean isForCat() {
        return forCat;
    }

    public boolean isForDog() {
        return forDog;
    }

    public boolean isForOther() {
        return forOther;
    }

    public GiftKind getKind() {
        return kind;
    }

    public Integer getMinLevel() {
        return minLevel;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public GiftCatalogStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }
}
