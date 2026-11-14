package com.tailtopia.gift.domain;

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
 * 礼物分类（V1.5.0 gift-pack Story 1-1 · D-1 · 表 {@code gift_categories}）。名称 EN / ID 各自不分大小写唯一
 * （含已下线），只下线不删除。对外接口用数字 id（参考数据例外，D-4）。
 */
@Entity
@Table(name = "gift_categories")
public class GiftCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_en", nullable = false, length = 40)
    private String nameEn;

    @Column(name = "name_id", nullable = false, length = 40)
    private String nameId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GiftCategoryStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected GiftCategory() {
    }

    public static GiftCategory create(String nameEn, String nameId, int sortOrder, Long updatedBy) {
        GiftCategory c = new GiftCategory();
        c.nameEn = nameEn;
        c.nameId = nameId;
        c.sortOrder = sortOrder;
        c.status = GiftCategoryStatus.ONLINE;
        c.updatedBy = updatedBy;
        return c;
    }

    /** 后台编辑（Story 1-2）：名称 / 排序；已下线的分类仍可改。 */
    public void edit(String nameEn, String nameId, int sortOrder, Long updatedBy) {
        this.nameEn = nameEn;
        this.nameId = nameId;
        this.sortOrder = sortOrder;
        this.updatedBy = updatedBy;
    }

    /** 上 / 下线（Story 1-2）。只下线不删除。 */
    public void setStatus(GiftCategoryStatus status, Long updatedBy) {
        this.status = status;
        this.updatedBy = updatedBy;
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

    public boolean isOnline() {
        return status == GiftCategoryStatus.ONLINE;
    }

    public Long getId() {
        return id;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getNameId() {
        return nameId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public GiftCategoryStatus getStatus() {
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
