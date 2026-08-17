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

    // ---------- 支付拆分（Story 3.4，建单时固化，🔴 不随后续部分退款重算） ----------
    @Enumerated(EnumType.STRING)
    @Column(name = "pay_channel", length = 16)
    private com.tailtopia.pay.domain.PayChannel payChannel;
    @Column(name = "coin_amount")
    private Long coinAmount;
    @Column(name = "cash_amount")
    private Long cashAmount;

    /**
     * 支付窗截止时刻（AD-8：下单 +60min）。🔴 <b>服务端时刻是唯一权威</b> ——
     * 客户端倒计时只是展示；用本地计时判定，改一下手机时间就能无限延长锁库存的时间，
     * 而库存是别人也想买的东西。
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** 本单当前的支付意图 token（到账事件据此回找订单）。纯 PawCoin 单无意图，恒 null。 */
    @Column(name = "payment_intent_token", length = 64)
    private String paymentIntentToken;

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
        // 🔴 AD-8：60 分钟支付窗。窗到即取消并释放库存 —— 锁着别人买不到的库存等一个
        //    可能永远不会来的付款，是自营模式下最贵的一种沉默损失。
        o.expiresAt = o.createdAt.plus(PAYMENT_WINDOW);
        return o;
    }

    /** 支付窗长度（AD-8）。🔴 与 App 端倒计时同源：客户端只展示服务端给的截止时刻。 */
    public static final java.time.Duration PAYMENT_WINDOW = java.time.Duration.ofMinutes(60);

    /** 是否已过支付窗（无窗的历史订单恒 false —— 它们不参与超时扫描）。 */
    public boolean isPaymentExpiredAt(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getPaymentIntentToken() {
        return paymentIntentToken;
    }

    /** 绑定支付意图（同一订单重复点「去支付」经幂等键取回同一个意图，故这里是幂等赋值）。 */
    public void attachPaymentIntent(String token) {
        this.paymentIntentToken = token;
        this.updatedAt = Instant.now();
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

    /**
     * 固化支付拆分（Story 3.4）。
     *
     * <p>🔴 <b>只在建单时调用一次</b>：三段金额与 channel 一旦写下就不再重算 ——
     * 部分退款会改变实付比例，若重算，退到一半时「已退多少 Coin」就没有稳定的分母了（AD-2）。
     */
    public void applyPaymentSplit(com.tailtopia.pay.domain.PayChannel channel,
            PaymentSplit split) {
        this.payChannel = channel;
        this.coinAmount = split.coinAmount();
        this.cashAmount = split.cashAmount();
        this.updatedAt = Instant.now();
    }

    public com.tailtopia.pay.domain.PayChannel getPayChannel() {
        return payChannel;
    }

    public Long getCoinAmount() {
        return coinAmount;
    }

    public Long getCashAmount() {
        return cashAmount;
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
