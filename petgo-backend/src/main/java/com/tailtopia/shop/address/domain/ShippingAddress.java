package com.tailtopia.shop.address.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 收货地址（Story 2.1，FR-98）。
 *
 * <p>🔒 <b>本实体含三项 PII</b>：{@code receiverName} / {@code receiverPhone} / {@code addressLine}。
 * 任何日志、异常 detail、埋点都<b>绝不得</b>出现它们（NFR-5）。
 *
 * <p>🔴 {@code receiverPhone} 与<b>账号手机号不互通、不自动填充、不同步</b>——
 * 收件人可以根本不是账号主人（替家人下单在印尼很常见）。
 */
@Entity
@Table(name = "shipping_addresses")
public class ShippingAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 🔒 PII */
    @Column(name = "receiver_name", nullable = false, length = 40)
    private String receiverName;

    /** 🔒 PII。E.164 归一化后的值，写入前必过 {@link IndonesiaPhone#normalize}。 */
    @Column(name = "receiver_phone", nullable = false, length = 16)
    private String receiverPhone;

    @Column(name = "provinsi", nullable = false, length = 60)
    private String provinsi;

    @Column(name = "kota_kabupaten", nullable = false, length = 60)
    private String kotaKabupaten;

    /** 运费与服务范围的判定粒度（FR-99）。 */
    @Column(name = "kecamatan", nullable = false, length = 60)
    private String kecamatan;

    /** 🔒 PII */
    @Column(name = "address_line", nullable = false, length = 120)
    private String addressLine;

    @Column(name = "kode_pos", nullable = false, length = 5)
    private String kodePos;

    @Column(name = "label", length = 10)
    private String label;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShippingAddress() {
    }

    public static ShippingAddress create(long userId, String publicToken, AddressFields f) {
        ShippingAddress a = new ShippingAddress();
        a.userId = userId;
        a.publicToken = publicToken;
        a.apply(f);
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    /** 编辑。🔴 不改 userId / publicToken / isDefault —— 默认态由服务层的专用方法管。 */
    public void apply(AddressFields f) {
        this.receiverName = f.receiverName();
        this.receiverPhone = f.receiverPhone();
        this.provinsi = f.provinsi();
        this.kotaKabupaten = f.kotaKabupaten();
        this.kecamatan = f.kecamatan();
        this.addressLine = f.addressLine();
        this.kodePos = f.kodePos();
        this.label = f.label();
        this.updatedAt = Instant.now();
    }

    public void markDefault(boolean value) {
        this.isDefault = value;
        this.updatedAt = Instant.now();
    }

    /** 结算选用时打点，供「删除默认后谁升为默认」排序。 */
    public void touchUsed() {
        this.lastUsedAt = Instant.now();
        this.updatedAt = this.lastUsedAt;
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

    public Long getUserId() {
        return userId;
    }

    /** 🔒 PII —— 调用方不得写入日志。 */
    public String getReceiverName() {
        return receiverName;
    }

    /** 🔒 PII —— 调用方不得写入日志。 */
    public String getReceiverPhone() {
        return receiverPhone;
    }

    public String getProvinsi() {
        return provinsi;
    }

    public String getKotaKabupaten() {
        return kotaKabupaten;
    }

    public String getKecamatan() {
        return kecamatan;
    }

    /** 🔒 PII —— 调用方不得写入日志。 */
    public String getAddressLine() {
        return addressLine;
    }

    public String getKodePos() {
        return kodePos;
    }

    public String getLabel() {
        return label;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
