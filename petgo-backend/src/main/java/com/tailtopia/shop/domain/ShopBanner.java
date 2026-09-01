package com.tailtopia.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Toko 顶部 banner（2026-08-27）。
 *
 * <p><b>同一时间只展示一张</b>：表里可以配多条，但取用时只取「已上架 + 权重最高」的那一条
 * （见 {@code ShopBannerRepository#pickActive}）。不做轮播 —— 轮播的第二张之后经常
 * 没被看到就被划走，收益不抵实现与运营成本。
 *
 * <p>🔴 <b>本版本纯展示、不可点</b>，故实体上没有任何跳转目标字段。
 * 宁可以后加列，也不要现在放一个恒为空的 link —— 空字段会让下一个人以为
 * 「跳转已经做了只是没配」，进而在客户端写出永远走不到的分支。
 *
 * <p>{@code imageW / imageH} 与 {@link ShopProduct} 同一口径：只存原始像素、不存比例，
 * 客户端据此按屏宽算出高度，避免图片到达前后布局跳动。手填 objectKey 的兜底路径给不出
 * 尺寸，此时两者为 null，客户端走默认比例。
 */
@Entity
@Table(name = "shop_banners")
public class ShopBanner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 图片：OSS objectKey，<b>非 URL</b> —— 签名 URL 禁入库与日志（NFR-5）。 */
    @Column(name = "image_key", nullable = false, length = 255)
    private String imageKey;

    @Column(name = "image_w")
    private Integer imageW;

    @Column(name = "image_h")
    private Integer imageH;

    /**
     * 是否上架。
     *
     * <p>🔴 新建恒为 {@code false}，与商品同一安全默认（Story 1.5）：
     * 新建即可见会让运营在还没检查图的时候，就把它推到了所有用户的首屏。
     */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** 取用权重，越大越优先；同权重按 id 倒序（后建的优先）。 */
    @Column(name = "sort_weight", nullable = false)
    private int sortWeight;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopBanner() {
    }

    /** 新建 banner。<b>默认未上架</b>，由运营主动上架。 */
    public static ShopBanner create(String imageKey, Integer imageW, Integer imageH,
            int sortWeight) {
        ShopBanner b = new ShopBanner();
        b.apply(imageKey, imageW, imageH, sortWeight);
        b.active = false;
        return b;
    }

    /**
     * 编辑。上架状态不经此方法改动（走 {@link #activate()} / {@link #deactivate()}）。
     *
     * <p>⚠️ {@code imageW / imageH} 必须与 {@code imageKey} <b>成对</b>传入：换了图却没换尺寸，
     * 客户端会按旧比例预置高度，banner 区高度与图对不上。两者同为 {@code Integer}，
     * <b>传反不会编译报错</b>，看准顺序（先宽后高）。
     */
    public void apply(String imageKey, Integer imageW, Integer imageH, int sortWeight) {
        this.imageKey = imageKey;
        this.imageW = imageW;
        this.imageH = imageH;
        this.sortWeight = sortWeight;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
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

    public String getImageKey() {
        return imageKey;
    }

    /** 原始像素宽；null = 未知（手填 key），客户端走默认比例。 */
    public Integer getImageW() {
        return imageW;
    }

    /** 原始像素高；null = 未知。 */
    public Integer getImageH() {
        return imageH;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortWeight() {
        return sortWeight;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
