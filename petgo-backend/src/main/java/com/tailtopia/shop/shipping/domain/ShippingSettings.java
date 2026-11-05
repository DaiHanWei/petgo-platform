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

    /**
     * S-7 退货收件地址：用户<b>自寄</b>到这里。
     *
     * <p>🔴 <b>本版本不做上门取件</b>（需承运商 API 与商务账号）。这三列为空时，
     * 退货申请页不展示寄回地址区块 —— 展示一个空地址比不展示更糟。
     */
    @Column(name = "return_address_text", length = 300)
    private String returnAddressText;
    @Column(name = "return_receiver_name", length = 60)
    private String returnReceiverName;
    @Column(name = "return_receiver_phone", length = 20)
    private String returnReceiverPhone;

    /**
     * 发货地（v1.4.2 Story 1.1，D-9 / D-10；V20261105_1605）。邮编用于 Biteship 询价，
     * 联系人 / 电话 / 地址供 5.1 下运单。🔴 电话与地址不得进日志。
     */
    @Column(name = "origin_postal_code", length = 5)
    private String originPostalCode;
    @Column(name = "origin_contact_name", length = 60)
    private String originContactName;
    @Column(name = "origin_contact_phone", length = 20)
    private String originContactPhone;
    @Column(name = "origin_address", length = 300)
    private String originAddress;

    /** 免运减免封顶（最小币种单位）；空或 0 = 不设额外封顶（m7）。计价在 1.2 计价器。 */
    @Column(name = "free_shipping_cap")
    private Long freeShippingCap;

    /** 保价费率（基点，50 = 0.5%，D-7）。计价在 1.2 计价器。 */
    @Column(name = "insurance_rate_bp", nullable = false)
    private int insuranceRateBp = DEFAULT_INSURANCE_RATE_BP;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 与迁移默认值一致：0.5%。 */
    public static final int DEFAULT_INSURANCE_RATE_BP = 50;

    protected ShippingSettings() {
    }

    public void applyThreshold(long value) {
        this.freeShippingThreshold = value;
        this.updatedAt = Instant.now();
    }

    public long getFreeShippingThreshold() {
        return freeShippingThreshold;
    }

    /** 配置退货收件地址（AB-11C 增配项）。三项要么都填，要么都留空。 */
    public void applyReturnAddress(String addressText, String receiverName, String receiverPhone) {
        this.returnAddressText = blankToNull(addressText);
        this.returnReceiverName = blankToNull(receiverName);
        this.returnReceiverPhone = blankToNull(receiverPhone);
        this.updatedAt = Instant.now();
    }

    public String getReturnAddressText() {
        return returnAddressText;
    }

    public String getReturnReceiverName() {
        return returnReceiverName;
    }

    public String getReturnReceiverPhone() {
        return returnReceiverPhone;
    }

    /** 配置齐了才算可用 —— 只填一半的地址寄不到。 */
    public boolean hasReturnAddress() {
        return returnAddressText != null && returnReceiverName != null
                && returnReceiverPhone != null;
    }

    /**
     * 配置发货地（v1.4.2 Story 1.1）。邮编可空（空 = 未配置，live / sandbox 下询价失败）；
     * 非空必须 5 位、首位非 0。
     */
    public void applyOrigin(String postalCode, String contactName, String contactPhone,
            String address) {
        String pc = blankToNull(postalCode);
        if (pc != null && !pc.matches("^[1-9][0-9]{4}$")) {
            throw new IllegalArgumentException("postal code must be 5 digits, not starting with 0");
        }
        requireMaxLength(contactName, 60, "origin contact name");
        requireMaxLength(contactPhone, 20, "origin contact phone");
        requireMaxLength(address, 300, "origin address");
        this.originPostalCode = pc;
        this.originContactName = blankToNull(contactName);
        this.originContactPhone = blankToNull(contactPhone);
        this.originAddress = blankToNull(address);
        this.updatedAt = Instant.now();
    }

    /** 设置免运封顶；{@code null} 或 0 = 不封顶；负数 → {@link IllegalArgumentException}。 */
    public void applyFreeShippingCap(Long cap) {
        if (cap != null && cap < 0) {
            throw new IllegalArgumentException("free shipping cap must be >= 0");
        }
        this.freeShippingCap = cap;
        this.updatedAt = Instant.now();
    }

    /** 设置保价费率（基点 0 ~ 10000）。 */
    public void applyInsuranceRateBp(int bp) {
        if (bp < 0 || bp > 10000) {
            throw new IllegalArgumentException("insurance rate bp must be within 0..10000");
        }
        this.insuranceRateBp = bp;
        this.updatedAt = Instant.now();
    }

    public String getOriginPostalCode() {
        return originPostalCode;
    }

    public String getOriginContactName() {
        return originContactName;
    }

    public String getOriginContactPhone() {
        return originContactPhone;
    }

    public String getOriginAddress() {
        return originAddress;
    }

    public Long getFreeShippingCap() {
        return freeShippingCap;
    }

    public int getInsuranceRateBp() {
        return insuranceRateBp;
    }

    private static void requireMaxLength(String v, int max, String field) {
        String t = blankToNull(v);
        if (t != null && t.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
