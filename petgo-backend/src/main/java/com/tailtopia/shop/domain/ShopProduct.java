package com.tailtopia.shop.domain;

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
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 自营商品（Story 1.1，建 {@code shop_products} 表；FR-94）。系统内首次出现的<b>实物商品</b>——
 * 既有全部收费场景（AI 问诊解锁 / 兽医咨询 / PawCoin 充值 / 高清图下载）都是虚拟商品。
 *
 * <p>对外只暴露不可枚举 {@link #publicToken}（{@code ShopTokenGenerator} 生成，
 * <b>绝不由自增 id 派生</b>，CLAUDE.md 强制护栏）；自增 {@code id} 仅作内部主键与外键，
 * 不出现在任何对外 JSON 中。
 *
 * <p>价格与库存不在本实体：<b>SKU 是价格与库存的实际承载者</b>（{@link ShopSku}，FR-94A）；
 * 库存表 {@code sku_inventory} 属 Story 1.2，本 Story 不建。
 */
@Entity
@Table(name = "shop_products")
public class ShopProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, updatable = false)
    private String publicToken;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "brand", nullable = false, length = 60)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private ProductCategory category;

    /** 主图：OSS objectKey，<b>非 URL</b>——签名 URL 禁入库与日志（NFR-5）。 */
    @Column(name = "main_image_key", nullable = false, length = 255)
    private String mainImageKey;

    /** 图集 ≤8：objectKey 列表，同样非 URL。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gallery_keys")
    private List<String> galleryKeys;

    @Enumerated(EnumType.STRING)
    @Column(name = "species", nullable = false, length = 16)
    private Species species;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_size", length = 16)
    private BodySize bodySize;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_stage", length = 16)
    private AgeStage ageStage;

    @Column(name = "detail_html", nullable = false)
    private String detailHtml;

    /**
     * 每日建议喂量（FR-94 ⑩）。🔴 <b>结构化数组，禁自由文本</b>——FR-109 的唯一计算依据。
     * 「Makanan 品类必填」的业务校验属 Story 1.3 后台录入，本实体只保证结构可存可取。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feeding_guide")
    private List<FeedingGuideEntry> feedingGuide;

    @Column(name = "shelf_life_note", nullable = false, length = 120)
    private String shelfLifeNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_policy", nullable = false, length = 24)
    private ReturnPolicy returnPolicy = ReturnPolicy.NO_RETURN_AFTER_OPEN;

    /** 运营精选排序权重（维护逻辑属 Story 1.5，本 Story 只建字段）。 */
    @Column(name = "sort_weight", nullable = false)
    private int sortWeight;

    /** 上下架（维护逻辑属 Story 1.5）。新建默认未上架，由运营主动上架。 */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopProduct() {
    }

    /**
     * 创建商品（Story 1.3 后台录入）。<b>状态变更集中在实体方法</b>，照 {@code PaymentIntent} 范式；
     * 不暴露 setter，避免调用方绕过不变式。
     *
     * <p>🔴 {@code publicToken} 由调用方经 {@code ShopTokenGenerator} 生成传入，
     * <b>绝不由自增 id 派生</b>（CLAUDE.md 护栏）。新建默认<b>未上架</b>，由运营主动上架（Story 1.5）。
     */
    public static ShopProduct create(String publicToken, String name, String brand,
            ProductCategory category, String mainImageKey, List<String> galleryKeys,
            Species species, BodySize bodySize, AgeStage ageStage, String detailHtml,
            List<FeedingGuideEntry> feedingGuide, String shelfLifeNote,
            ReturnPolicy returnPolicy, int sortWeight) {
        ShopProduct p = new ShopProduct();
        p.publicToken = publicToken;
        p.apply(name, brand, category, mainImageKey, galleryKeys, species, bodySize, ageStage,
                detailHtml, feedingGuide, shelfLifeNote, returnPolicy, sortWeight);
        p.active = false;
        return p;
    }

    /** 编辑商品（Story 1.3）。上架状态与 token 不经此方法改动。 */
    public void apply(String name, String brand, ProductCategory category, String mainImageKey,
            List<String> galleryKeys, Species species, BodySize bodySize, AgeStage ageStage,
            String detailHtml, List<FeedingGuideEntry> feedingGuide, String shelfLifeNote,
            ReturnPolicy returnPolicy, int sortWeight) {
        this.name = name;
        this.brand = brand;
        this.category = category;
        this.mainImageKey = mainImageKey;
        this.galleryKeys = galleryKeys;
        this.species = species;
        this.bodySize = bodySize;
        this.ageStage = ageStage;
        this.detailHtml = detailHtml;
        this.feedingGuide = feedingGuide;
        this.shelfLifeNote = shelfLifeNote;
        this.returnPolicy = returnPolicy;
        this.sortWeight = sortWeight;
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

    public String getPublicToken() {
        return publicToken;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public String getMainImageKey() {
        return mainImageKey;
    }

    public List<String> getGalleryKeys() {
        return galleryKeys;
    }

    public Species getSpecies() {
        return species;
    }

    public BodySize getBodySize() {
        return bodySize;
    }

    public AgeStage getAgeStage() {
        return ageStage;
    }

    public String getDetailHtml() {
        return detailHtml;
    }

    public List<FeedingGuideEntry> getFeedingGuide() {
        return feedingGuide;
    }

    public String getShelfLifeNote() {
        return shelfLifeNote;
    }

    public ReturnPolicy getReturnPolicy() {
        return returnPolicy;
    }

    public int getSortWeight() {
        return sortWeight;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
