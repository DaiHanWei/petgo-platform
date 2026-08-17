package com.tailtopia.shop.shipping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 全局配送设置（单例行，范式同 pricing_config）。 */
@Entity
@Table(name = "shipping_settings")
public class ShippingSettings {

    /** 单例主键，恒为 1（DB CHECK 兜住）。 */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id;

    /** 商品小计 ≥ 此值免运费；0 = 不做免运。 */
    @Column(name = "free_shipping_threshold", nullable = false)
    private long freeShippingThreshold;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShippingSettings() {
    }

    public void applyThreshold(long value) {
        this.freeShippingThreshold = value;
        this.updatedAt = Instant.now();
    }

    public long getFreeShippingThreshold() {
        return freeShippingThreshold;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
