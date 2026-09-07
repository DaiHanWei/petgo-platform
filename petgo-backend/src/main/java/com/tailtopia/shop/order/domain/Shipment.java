package com.tailtopia.shop.order.domain;

import com.tailtopia.shared.error.AppException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 包裹（Story 4.1，S-2 一单多包的持久化依据）。
 *
 * <p>一个订单 1..N 个包裹，每包自带承运商 / 单号 / 承运成本 / 送达时刻。
 * 🔴 <b>订单转 {@code DELIVERED} 的条件是所有包裹都送达；7 日自动确认以最后一个包裹送达为起点。</b>
 *
 * <p>🔒 {@code trackingNo} <b>非 PII，可记日志</b>（NFR-5）；但同上下文的收件人姓名 / 电话 /
 * 详细地址严禁记录 —— 单号本身查不出人，单号加姓名就能。
 */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_order_id", nullable = false, updatable = false)
    private Long shopOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", nullable = false, length = 16)
    private Carrier carrier;

    @Column(name = "tracking_no", nullable = false, length = 64)
    private String trackingNo;

    /** S-11 承运成本：运单实际金额。AB-13A 毛利口径依赖它。 */
    @Column(name = "carrier_cost", nullable = false)
    private long carrierCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShipmentStatus status;

    @Column(name = "shipped_at", nullable = false)
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shipment() {
    }

    public static Shipment ship(long shopOrderId, Carrier carrier, String trackingNo,
            long carrierCost) {
        if (trackingNo == null || trackingNo.isBlank()) {
            throw AppException.validation("请填写物流单号");
        }
        if (trackingNo.trim().length() > 64) {
            throw AppException.validation("物流单号过长");
        }
        if (carrierCost < 0) {
            throw AppException.validation("承运成本不能为负");
        }
        Shipment s = new Shipment();
        s.shopOrderId = shopOrderId;
        s.carrier = carrier;
        s.trackingNo = trackingNo.trim();
        s.carrierCost = carrierCost;
        s.status = ShipmentStatus.SHIPPED;
        s.shippedAt = Instant.now();
        s.createdAt = s.shippedAt;
        s.updatedAt = s.shippedAt;
        return s;
    }

    /**
     * 标记本包裹送达。
     *
     * <p>幂等：已送达的包裹重复标记不报错也<b>不改写送达时刻</b> ——
     * 时刻一旦被第二次标记推后，退货窗口就跟着往后挪，用户白得几天、平台多担几天。
     */
    public void markDelivered(Instant at) {
        if (status == ShipmentStatus.DELIVERED) {
            return;
        }
        this.status = ShipmentStatus.DELIVERED;
        this.deliveredAt = at;
        this.updatedAt = Instant.now();
    }

    public boolean isDelivered() {
        return status == ShipmentStatus.DELIVERED;
    }

    public Long getId() {
        return id;
    }

    public Long getShopOrderId() {
        return shopOrderId;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public long getCarrierCost() {
        return carrierCost;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    /** 🔒 单号可打印（非 PII）；本类本就不持有姓名/电话/地址，无可泄露之物。 */
    @Override
    public String toString() {
        return "Shipment[" + carrier + " " + trackingNo + ", " + status + "]";
    }
}
