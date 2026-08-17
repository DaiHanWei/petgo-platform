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

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
