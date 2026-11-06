package com.tailtopia.shop.shipping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一个可配送区域及其固定运费（Story 2.2，FR-99 / C-14）。
 *
 * <p>🔴 <b>没有「配送方式」维度</b> —— C-14 已把二维运费表降为一维，本版本只有 Reguler 一档。
 */
@Entity
@Table(name = "shipping_zones")
public class ShippingZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kecamatan", nullable = false, length = 60)
    private String kecamatan;

    @Column(name = "kota_kabupaten", nullable = false, length = 60)
    private String kotaKabupaten;

    @Column(name = "provinsi", nullable = false, length = 60)
    private String provinsi;

    @Column(name = "fee", nullable = false)
    private long fee;

    /**
     * 区域代表邮编（v1.4.2 Story 1.1，D-9；V20261105_1605）：游客按地区询价用。
     * 为空 = 该区游客询价按「不支持配送」。5 位、首位非 0。
     */
    @Column(name = "postal_code", length = 5)
    private String postalCode;

    /** 🔴 下架用 false 而非删行——历史订单的运费需可追溯（AB-13D）。 */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShippingZone() {
    }

    public static ShippingZone create(String kecamatan, String kotaKabupaten, String provinsi,
            long fee) {
        ShippingZone z = new ShippingZone();
        z.kecamatan = kecamatan;
        z.kotaKabupaten = kotaKabupaten;
        z.provinsi = provinsi;
        z.fee = fee;
        z.active = true;
        z.createdAt = Instant.now();
        z.updatedAt = z.createdAt;
        return z;
    }

    public void apply(String kotaKabupaten, String provinsi, long fee) {
        this.kotaKabupaten = kotaKabupaten;
        this.provinsi = provinsi;
        this.fee = fee;
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean value) {
        this.active = value;
        this.updatedAt = Instant.now();
    }

    /** 设置区域代表邮编；空白视为清空。格式不对 → {@link IllegalArgumentException}。 */
    public void applyPostalCode(String value) {
        String v = value == null || value.isBlank() ? null : value.trim();
        if (v != null && !v.matches("^[1-9][0-9]{4}$")) {
            throw new IllegalArgumentException("postal code must be 5 digits, not starting with 0");
        }
        this.postalCode = v;
        this.updatedAt = Instant.now();
    }

    public String getPostalCode() {
        return postalCode;
    }

    public Long getId() {
        return id;
    }

    public String getKecamatan() {
        return kecamatan;
    }

    public String getKotaKabupaten() {
        return kotaKabupaten;
    }

    public String getProvinsi() {
        return provinsi;
    }

    public long getFee() {
        return fee;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
