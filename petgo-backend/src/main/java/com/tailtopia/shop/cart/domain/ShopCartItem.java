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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopCartItem() {
    }

    public static ShopCartItem of(long cartId, long skuId, int qty) {
        ShopCartItem i = new ShopCartItem();
        i.cartId = cartId;
        i.skuId = skuId;
        i.qty = qty;
        i.createdAt = Instant.now();
        i.updatedAt = i.createdAt;
        return i;
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
