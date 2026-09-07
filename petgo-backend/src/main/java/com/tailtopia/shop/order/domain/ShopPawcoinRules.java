package com.tailtopia.shop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * PawCoin 电商消费规则（FR-100A 规则 2/3/4）。单例行。
 *
 * <p>建表与读路径属 Story 3.4；<b>后台配置页属 Story 3.5 / AB-6D</b>。
 */
@Entity
@Table(name = "shop_pawcoin_rules")
public class ShopPawcoinRules {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "allow_shipping_deduction", nullable = false)
    private boolean allowShippingDeduction;

    /**
     * 🔴 用途是<b>故障/欺诈的爆炸半径 + DEP-7 监管姿态</b>，<b>不是控浮存</b>——
     * 定低反而有害（L-7 自纠）：只会把大额订单挤到纯现金，既不减少浮存，
     * 又损失了 Coin 的消耗出口。
     */
    @Column(name = "max_coin_per_order", nullable = false)
    private long maxCoinPerOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopPawcoinRules() {
    }

    public void apply(boolean enabled, boolean allowShippingDeduction, long maxCoinPerOrder) {
        this.enabled = enabled;
        this.allowShippingDeduction = allowShippingDeduction;
        this.maxCoinPerOrder = maxCoinPerOrder;
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAllowShippingDeduction() {
        return allowShippingDeduction;
    }

    public long getMaxCoinPerOrder() {
        return maxCoinPerOrder;
    }
}
