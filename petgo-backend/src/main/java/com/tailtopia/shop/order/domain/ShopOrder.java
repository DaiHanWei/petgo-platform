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

    // ---------- 履约时刻（Story 4.1） ----------
    @Column(name = "shipped_at")
    private Instant shippedAt;
    /**
     * 🔴 <b>SPEC-5：「签收」= 订单进入 {@code DELIVERED} 的时刻。</b>
     * Epic 5 的 7 日退货窗口以此起算，<b>自动确认收货不清空它</b> ——
     * 该词在 PRD 里出现四次却既不在术语表也不是状态名，不定义则退货窗口在实现层没有起点。
     */
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    /** SPEC-2 三条出口的留痕：走的是哪一条。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_source", length = 24)
    private DeliverySource deliverySource;
    @Enumerated(EnumType.STRING)
    @Column(name = "completion_source", length = 24)
    private CompletionSource completionSource;

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

    // ---------- 履约段（Story 4.1） ----------

    /**
     * 自动确认收货的两段时长（S-1 已定 M = 7）。
     *
     * <p>标准快递 2–4 日 + 3 日冗余。最坏路径：D7 置 {@code DELIVERED} → D14 置 {@code COMPLETED}，
     * 退货窗口 D7–D14。
     */
    public static final java.time.Duration AUTO_DELIVER_AFTER = java.time.Duration.ofDays(7);
    public static final java.time.Duration AUTO_COMPLETE_AFTER = java.time.Duration.ofDays(7);
    /** 🔴 Epic 5 退货窗口长度，自「签收」（进入 {@code DELIVERED}）起算。 */
    public static final java.time.Duration RETURN_WINDOW = java.time.Duration.ofDays(7);

    /** 首个包裹发出：订单转 {@code SHIPPED} 并记录发货时刻（M 日自动送达以此起算）。 */
    public void markShipped(Instant at) {
        if (status == ShopOrderStatus.SHIPPED) {
            return;     // 一单多包：第二个包裹不再改写发货时刻
        }
        transitionTo(ShopOrderStatus.SHIPPED);
        this.shippedAt = at;
    }

    /**
     * 订单送达（SPEC-2 三条出口共用）。
     *
     * <p>🔴 <b>送达时刻只写一次</b>：{@code deliveredAt} 是退货窗口的锚点，被第二次标记推后
     * 就等于凭空延长退货期。
     */
    public void markDelivered(Instant at, DeliverySource source) {
        if (status == ShopOrderStatus.DELIVERED) {
            return;
        }
        transitionTo(ShopOrderStatus.DELIVERED);
        this.deliveredAt = at;
        this.deliverySource = source;
    }

    /**
     * 订单完成。
     *
     * <p>🔴 <b>自动确认后仍保留自签收起算 7 日的退货窗口</b>，两者不冲突 ——
     * 「已完成」说的是履约结束，不是「不能再退」。把两件事绑在一起，用户就会因为系统替他
     * 点了确认收货而失去退货权。
     */
    public void markCompleted(Instant at, CompletionSource source) {
        if (status == ShopOrderStatus.COMPLETED) {
            return;
        }
        transitionTo(ShopOrderStatus.COMPLETED);
        this.completedAt = at;
        this.completionSource = source;
    }

    /** 退货窗口截止（Epic 5 用）。未签收则无窗口。 */
    public Instant returnWindowEndsAt() {
        return deliveredAt == null ? null : deliveredAt.plus(RETURN_WINDOW);
    }

    /** 🔴 已完成的订单在窗口内依然可退（AC：自动确认与退货窗口并存）。 */
    public boolean isWithinReturnWindow(Instant now) {
        Instant end = returnWindowEndsAt();
        return end != null && !now.isAfter(end);
    }

    /** 发货起已超 M 日仍无任何送达标记 → 该由 {@code @Scheduled} 兜底置送达。 */
    public boolean isAutoDeliverDue(Instant now) {
        return status == ShopOrderStatus.SHIPPED && shippedAt != null
                && now.isAfter(shippedAt.plus(AUTO_DELIVER_AFTER));
    }

    /** 送达起已超 7 日用户仍未确认 → 自动完成（FR-102）。 */
    public boolean isAutoCompleteDue(Instant now) {
        return status == ShopOrderStatus.DELIVERED && deliveredAt != null
                && now.isAfter(deliveredAt.plus(AUTO_COMPLETE_AFTER));
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    /** 🔴 「签收」时刻（SPEC-5）。 */
    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public DeliverySource getDeliverySource() {
        return deliverySource;
    }

    public CompletionSource getCompletionSource() {
        return completionSource;
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

    /**
     * 累计已退金额（Story 4.4 异常取消；Epic 5 的整数累计法沿用同两列，AD-2）。
     *
     * <p>🔴 <b>累加而非覆盖</b>：多次部分退款必须叠加 —— 覆盖会让「已退多少」在第二次退款后
     * 变成只剩最后一次的数字，而全额退款是否精确归零正是靠这个累计值判定的。
     */
    public void recordRefund(long total, long coin) {
        if (total < 0 || coin < 0) {
            throw AppException.validation("退款金额不能为负");
        }
        if (this.refundedTotal + total > this.totalAmount) {
            throw AppException.conflict("累计退款超过订单金额");
        }
        this.refundedTotal += total;
        this.refundedCoin += coin;
        this.updatedAt = Instant.now();
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
