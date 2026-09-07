package com.tailtopia.shop.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 购物车（Story 3.1，FR-96）。
 *
 * <p>🔴 <b>单店模型</b>：没有店铺/卖家维度——平台自营是唯一卖家。
 * <p>🔴 <b>与用户一对一</b>，没有匿名车的表达方式（游客无购物车，有意的能力缺席）。
 */
@Entity
@Table(name = "shop_carts")
public class ShopCart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopCart() {
    }

    public static ShopCart forUser(long userId) {
        ShopCart c = new ShopCart();
        c.userId = userId;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }
}
