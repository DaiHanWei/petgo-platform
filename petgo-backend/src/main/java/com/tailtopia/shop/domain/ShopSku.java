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

/**
 * 商品规格（Story 1.1，建 {@code shop_skus} 表；FR-94A）。
 *
 * <p><b>SKU 是价格与库存的实际承载者</b>：同一商品的不同规格可有不同价格、不同库存、
 * 不同退货规则标识。库存表 {@code sku_inventory} 属 Story 1.2，本 Story 不建。
 *
 * <p>🔴 {@link #getPrice()} 为<b>最小币种单位整型</b>（IDR 无小数）——禁 {@code DECIMAL}/{@code double}
 * （NFR-9 资金精度）。展示格式化 {@code Rp 185.000} 由前端负责。
 *
 * <p>{@link #getReturnPolicy()} 可空 = <b>继承商品级</b>，见 {@link #effectiveReturnPolicy}。
 */
@Entity
@Table(name = "shop_skus")
public class ShopSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, updatable = false)
    private String publicToken;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "spec_name", nullable = false, length = 40)
    private String specName;

    /** 最小币种单位整型（IDR 无小数）。禁 DECIMAL/double。 */
    @Column(name = "price", nullable = false)
    private long price;

    /** 净含量（克）。FR-109 粮量见底预估的输入之一；非 Makanan 可空。 */
    @Column(name = "net_weight_g")
    private Long netWeightG;

    /** 可空 = 继承商品级（FR-94A）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "return_policy", length = 24)
    private ReturnPolicy returnPolicy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopSku() {
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

    /**
     * 生效的退货规则：SKU 级为空时继承商品级（FR-94A）。
     *
     * <p>🔴 商品详情页展示的必须是这个值，不是原始 {@link #getReturnPolicy()}——否则未单独设置的
     * SKU 会显示为空，用户看不到「开封不退」这类关键约束（FR-104 要求商品详情页、结算页、
     * 退货申请页三处明示，此为第 1 处）。
     */
    public ReturnPolicy effectiveReturnPolicy(ReturnPolicy productLevel) {
        return returnPolicy != null ? returnPolicy : productLevel;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getProductId() {
        return productId;
    }

    public String getSpecName() {
        return specName;
    }

    public long getPrice() {
        return price;
    }

    public Long getNetWeightG() {
        return netWeightG;
    }

    public ReturnPolicy getReturnPolicy() {
        return returnPolicy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
