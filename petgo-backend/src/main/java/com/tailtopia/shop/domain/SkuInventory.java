package com.tailtopia.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * SKU 库存（Story 1.2，建 {@code sku_inventory} 表；FR-95）。
 *
 * <p>🔴 <b>可售库存 = {@code actual - locked}，查询期计算，绝不落第三列</b>——三列并存必然出现
 * 三值不一致，而库存不一致等于钱不一致。
 *
 * <p>🔴 <b>本实体不提供任何修改数量的方法</b>：一切增减都必须走
 * {@link com.tailtopia.shop.repository.SkuInventoryRepository} 的<b>单条条件原子 UPDATE</b>，
 * 依影响行数判定成败。<b>禁应用层「先查后改」</b>——那两步之间的窗口就是超卖发生的地方
 * （范式照 V1.0 决策 F11 兽医抢单 与 {@code PawCoinWalletRepository.applyDelta}）。
 */
@Entity
@Table(name = "sku_inventory")
public class SkuInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    /** 实际库存（仓库里真实有多少）。 */
    @Column(name = "actual", nullable = false)
    private long actual;

    /** 锁定库存（已下单未支付 / 未出库）。 */
    @Column(name = "locked", nullable = false)
    private long locked;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkuInventory() {
    }

    /** 可售库存 —— 查询期计算，不落库。 */
    public long available() {
        return actual - locked;
    }

    public Long getId() {
        return id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public long getActual() {
        return actual;
    }

    public long getLocked() {
        return locked;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
