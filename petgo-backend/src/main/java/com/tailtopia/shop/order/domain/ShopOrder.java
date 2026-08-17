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
 * 电商订单（Story 3.2）。
 *
 * <p>🔴 <b>{@code publicToken} 是对外展示的订单号</b>，SecureRandom + Base62 22 位，
 * 绝不外露 {@code id} 或 {@code seqNo}。
 *
 * <p>🔒 <b>收货地址是快照不是外键</b>（AD-13）：用户改地址簿不得改写历史订单的履约地址 ——
 * 订单上的地址是<b>履约凭证</b>，不是<b>当前偏好</b>。含三项 PII，日志禁记。
 */
@Entity
@Table(name = "shop_orders")
public class ShopOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    /** 🔴 对账用连续序号，绝不外露。 */
    @Column(name = "seq_no", insertable = false, updatable = false)
    private Long seqNo;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ShopOrderStatus status;

    @Column(name = "goods_subtotal", nullable = false, updatable = false)
    private long goodsSubtotal;

    @Column(name = "shipping_fee", nullable = false, updatable = false)
    private long shippingFee;

    /** 免运抵扣，≤ 0。 */
    @Column(name = "shipping_discount", nullable = false, updatable = false)
    private long shippingDiscount;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private long totalAmount;

    // ---------- 🔒 收货地址快照（PII，日志禁记） ----------
    @Column(name = "ship_receiver_name", nullable = false, updatable = false, length = 40)
    private String shipReceiverName;
    @Column(name = "ship_receiver_phone", nullable = false, updatable = false, length = 16)
    private String shipReceiverPhone;
    @Column(name = "ship_provinsi", nullable = false, updatable = false, length = 60)
    private String shipProvinsi;
    @Column(name = "ship_kota_kabupaten", nullable = false, updatable = false, length = 60)
    private String shipKotaKabupaten;
    @Column(name = "ship_kecamatan", nullable = false, updatable = false, length = 60)
    private String shipKecamatan;
    @Column(name = "ship_address_line", nullable = false, updatable = false, length = 120)
    private String shipAddressLine;
    @Column(name = "ship_kode_pos", nullable = false, updatable = false, length = 5)
    private String shipKodePos;

    // ---------- Epic 5 用（Epic 3 期间恒为初值） ----------
    @Column(name = "refunded_total", nullable = false)
    private long refundedTotal;
    @Column(name = "refunded_coin", nullable = false)
    private long refundedCoin;
    @Column(name = "is_full_return", nullable = false)
    private boolean fullReturn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopOrder() {
    }

    public static ShopOrder place(String publicToken, long userId, long goodsSubtotal,
            long shippingFee, long shippingDiscount, AddressSnapshot ship) {
        ShopOrder o = new ShopOrder();
        o.publicToken = publicToken;
        o.userId = userId;
        o.status = ShopOrderStatus.PENDING_PAYMENT;
        o.goodsSubtotal = goodsSubtotal;
        o.shippingFee = shippingFee;
        o.shippingDiscount = shippingDiscount;
        o.totalAmount = goodsSubtotal + shippingFee + shippingDiscount;
        o.shipReceiverName = ship.receiverName();
        o.shipReceiverPhone = ship.receiverPhone();
        o.shipProvinsi = ship.provinsi();
        o.shipKotaKabupaten = ship.kotaKabupaten();
        o.shipKecamatan = ship.kecamatan();
        o.shipAddressLine = ship.addressLine();
        o.shipKodePos = ship.kodePos();
        o.createdAt = Instant.now();
        o.updatedAt = o.createdAt;
        return o;
    }

    /**
     * 🔴 <b>状态迁移的唯一入口。</b>合法性判定在 {@link ShopOrderStatus#canTransitionTo}，
     * 非法迁移抛领域异常 → RFC 9457 ProblemDetail。
     *
     * <p>各 service 一律走这里，<b>不得直接 setStatus</b> —— 那正是状态机形同虚设的开始。
     */
    public void transitionTo(ShopOrderStatus next) {
        if (status == next) {
            return;     // 幂等：重复投递的支付回调不该报错
        }
        if (!status.canTransitionTo(next)) {
            throw AppException.conflict(
                    "订单状态不允许从 %s 变为 %s".formatted(status, next));
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    /** 🔴 对外展示的订单号。 */
    public String getPublicToken() {
        return publicToken;
    }

    /** 🔴 对账用，绝不下发给用户。 */
    public Long getSeqNo() {
        return seqNo;
    }

    public Long getUserId() {
        return userId;
    }

    public ShopOrderStatus getStatus() {
        return status;
    }

    public long getGoodsSubtotal() {
        return goodsSubtotal;
    }

    public long getShippingFee() {
        return shippingFee;
    }

    public long getShippingDiscount() {
        return shippingDiscount;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    /** 🔒 PII —— 不得写入日志。 */
    public AddressSnapshot shipTo() {
        return new AddressSnapshot(shipReceiverName, shipReceiverPhone, shipProvinsi,
                shipKotaKabupaten, shipKecamatan, shipAddressLine, shipKodePos);
    }

    public String getShipKecamatan() {
        return shipKecamatan;
    }

    public long getRefundedTotal() {
        return refundedTotal;
    }

    public long getRefundedCoin() {
        return refundedCoin;
    }

    public boolean isFullReturn() {
        return fullReturn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 🔒 不打印任何 PII。 */
    @Override
    public String toString() {
        return "ShopOrder[" + publicToken + ", " + status + ", PII omitted]";
    }
}
