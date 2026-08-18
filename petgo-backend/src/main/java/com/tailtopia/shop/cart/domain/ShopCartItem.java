package com.tailtopia.shop.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 购物车行（Story 3.1）。同一 SKU 一辆车里只有一行——再加购是加数量。 */
@Entity
@Table(name = "shop_cart_items")
public class ShopCartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cart_id", nullable = false, updatable = false)
    private Long cartId;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "qty", nullable = false)
    private int qty;

    /**
     * 加购入口 / 触发类型（Story 3.10，AB-13B 归因链）。
     *
     * <p>🔴 <b>只有加购那一刻知道商品是从哪个入口进来的</b>，所以必须在这里存下来 ——
     * 下单时抄到订单行，后台据此算「触发卡转化率 vs 普通曝光转化率」（判定 A-16）。
     * 两列可空：拿不到来源就写 NULL，那是诚实的「未知」，比编一个值强 ——
     * 错误的归因数据没人能事后识别。
     */
    @Column(name = "entry_source", length = 32)
    private String entrySource;

    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopCartItem() {
    }

    public static ShopCartItem of(long cartId, long skuId, int qty) {
        return of(cartId, skuId, qty, null, null);
    }

    /** 带归因的加购（Story 3.10）。既有 3 参工厂保留，调用点一行不改。 */
    public static ShopCartItem of(long cartId, long skuId, int qty, String entrySource,
            String triggerType) {
        ShopCartItem i = new ShopCartItem();
        i.cartId = cartId;
        i.skuId = skuId;
        i.qty = qty;
        i.entrySource = entrySource;
        i.triggerType = triggerType;
        i.createdAt = Instant.now();
        i.updatedAt = i.createdAt;
        return i;
    }

    /**
     * 记下加购来源。
     *
     * <p>🔴 <b>只在为空时写入（首次加购者优先）</b>：同一 SKU 第二次加购通常发生在
     * 用户已经决定要买之后（比如在购物车里 +1），此时的「入口」不是他做出购买决定的地方。
     * 让后来的动作覆盖首次来源，会把转化归给错的入口。
     */
    public void attributeIfAbsent(String entrySource, String triggerType) {
        if (this.entrySource == null && entrySource != null) {
            this.entrySource = entrySource;
            this.triggerType = triggerType;
            this.updatedAt = Instant.now();
        }
    }

    public String getEntrySource() {
        return entrySource;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setQty(int qty) {
        this.qty = qty;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCartId() {
        return cartId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getQty() {
        return qty;
    }
}
