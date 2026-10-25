package com.tailtopia.merchant.po.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 进货单发货包裹：一单可多包裹，TailTopia ops 发货时写入（契约 5.3 {@code Shipment}）。 */
@Entity
@Table(name = "purchase_order_shipments")
public class PurchaseOrderShipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private Long purchaseOrderId;

    /** 快递公司显示名，如 {@code JNE REG}，商家平台原样显示。 */
    @Column(name = "carrier", nullable = false, updatable = false, length = 32)
    private String carrier;

    @Column(name = "tracking_no", nullable = false, updatable = false, length = 64)
    private String trackingNo;

    /** 查询链接，只收 http/https 绝对地址（ops 表单校验）；可空。 */
    @Column(name = "tracking_url", updatable = false, length = 512)
    private String trackingUrl;

    @Column(name = "shipped_at", nullable = false, updatable = false)
    private Instant shippedAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PurchaseOrderShipment() {
    }

    public static PurchaseOrderShipment of(long purchaseOrderId, String carrier, String trackingNo,
            String trackingUrl, Instant shippedAt, Long createdBy) {
        PurchaseOrderShipment s = new PurchaseOrderShipment();
        s.purchaseOrderId = purchaseOrderId;
        s.carrier = carrier;
        s.trackingNo = trackingNo;
        s.trackingUrl = trackingUrl;
        s.shippedAt = shippedAt;
        s.createdBy = createdBy;
        s.createdAt = shippedAt;
        return s;
    }

    public Long getId() {
        return id;
    }

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public String getTrackingUrl() {
        return trackingUrl;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
