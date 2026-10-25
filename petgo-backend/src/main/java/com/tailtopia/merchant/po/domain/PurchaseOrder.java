package com.tailtopia.merchant.po.domain;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 门店进货单（契约第 2、5 节）。门店向 TailTopia 按批发价下的单，与 Toko 订单（{@code shop_orders}）分表 ——
 * 商家平台的返佣扫描只读 {@code shop_orders}，进货单天然不进返佣（FR-14）。
 *
 * <p>🔴 {@code publicToken}（报文 {@code po_token}）是唯一对外标识，自增 id 不出现在报文里。
 * {@code merchantStoreId} 只在内网服务端之间传递（门店身份头）。
 *
 * <p>🔒 收货地址快照与门店电话是 PII，日志禁记（{@link #toString()} 已屏蔽）。
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    /** 付款窗（契约 5.2）：建单 + 60 分钟。 */
    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(60);
    /** 发货起超过此时长仍未标记送达 → 自动置送达（沿用 Toko {@code ShopOrder.AUTO_DELIVER_AFTER}）。 */
    public static final Duration AUTO_DELIVER_AFTER = Duration.ofDays(7);
    /** 送达起超过此时长 → 自动完成（沿用 Toko {@code ShopOrder.AUTO_COMPLETE_AFTER}；门店端没有确认收货）。 */
    public static final Duration AUTO_COMPLETE_AFTER = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "merchant_store_id", nullable = false, updatable = false)
    private Long merchantStoreId;

    @Column(name = "order_no", nullable = false, updatable = false, length = 32)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PurchaseOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 32)
    private PoCancelReason cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method", nullable = false, updatable = false, length = 16)
    private PoShippingMethod shippingMethod;

    @Column(name = "goods_amount", nullable = false, updatable = false)
    private long goodsAmount;

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

    @Column(name = "store_name_snapshot", nullable = false, updatable = false, length = 80)
    private String storeNameSnapshot;
    /** 🔒 门店登录手机号快照（PII）。 */
    @Column(name = "store_phone_snapshot", nullable = false, updatable = false, length = 16)
    private String storePhoneSnapshot;

    @Column(name = "payment_deadline", nullable = false, updatable = false)
    private Instant paymentDeadline;

    @Column(name = "payment_intent_token", length = 64)
    private String paymentIntentToken;

    @Column(name = "paid_at")
    private Instant paidAt;
    @Column(name = "shipped_at")
    private Instant shippedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_source", length = 24)
    private PoDeliverySource deliverySource;

    /**
     * 🔴 乐观锁（同 {@code ShopOrder}）：支付回调与超时取消两个事务同时读到 {@code PENDING_PAYMENT} 时，
     * 后提交者整体回滚 —— 库存的 commit 与 release 不会双双落库。
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PurchaseOrder() {
    }

    /** 建单（{@code PENDING_PAYMENT}，付款截止 = {@code now} + 60 分钟）。 */
    public static PurchaseOrder place(String publicToken, String orderNo, long merchantStoreId,
            PoShippingMethod shippingMethod, long goodsAmount, AddressSnapshot ship,
            String storeName, String storePhone, Instant now) {
        // timestamptz 只存到微秒且四舍五入：先截到微秒，建单响应与之后读出的 payment_deadline 才逐位一致
        now = now.truncatedTo(ChronoUnit.MICROS);
        PurchaseOrder o = new PurchaseOrder();
        o.publicToken = publicToken;
        o.orderNo = orderNo;
        o.merchantStoreId = merchantStoreId;
        o.status = PurchaseOrderStatus.PENDING_PAYMENT;
        o.shippingMethod = shippingMethod;
        o.goodsAmount = goodsAmount;
        o.shipReceiverName = ship.receiverName();
        o.shipReceiverPhone = ship.receiverPhone();
        o.shipProvinsi = ship.provinsi();
        o.shipKotaKabupaten = ship.kotaKabupaten();
        o.shipKecamatan = ship.kecamatan();
        o.shipAddressLine = ship.addressLine();
        o.shipKodePos = ship.kodePos();
        o.storeNameSnapshot = storeName;
        o.storePhoneSnapshot = storePhone;
        o.paymentDeadline = now.plus(PAYMENT_WINDOW);
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    private void transitionTo(PurchaseOrderStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw AppException.conflict("进货单状态不允许从 %s 变为 %s".formatted(status, next));
        }
        this.status = next;
        this.updatedAt = now;
    }

    /** 契约 5.5：{@code now ≥ payment_deadline} 的待付款单不可再付，按已取消处理。 */
    public boolean isPaymentOverdueAt(Instant now) {
        return status == PurchaseOrderStatus.PENDING_PAYMENT && !now.isBefore(paymentDeadline);
    }

    /** 付款成功：写 {@code paid_at}（写入时刻，不取网关回调时间）。 */
    public void markPaid(Instant now) {
        transitionTo(PurchaseOrderStatus.PAID, now);
        this.paidAt = now;
    }

    /** 超时取消：{@code cancelled_at = payment_deadline}（契约 5.2），原因 {@code PAYMENT_TIMEOUT}。 */
    public void cancelForTimeout(Instant now) {
        transitionTo(PurchaseOrderStatus.CANCELLED, now);
        this.cancelReason = PoCancelReason.PAYMENT_TIMEOUT;
        this.cancelledAt = paymentDeadline;
    }

    /** 录入包裹：第一个包裹把单推到 {@code SHIPPED} 并写 {@code shipped_at}；之后的包裹只追加、不改状态（D5）。 */
    public void recordShipment(Instant shippedAt, Instant now) {
        if (status == PurchaseOrderStatus.PAID) {
            transitionTo(PurchaseOrderStatus.SHIPPED, now);
            this.shippedAt = shippedAt;
            return;
        }
        if (status != PurchaseOrderStatus.SHIPPED && status != PurchaseOrderStatus.DELIVERED
                && status != PurchaseOrderStatus.COMPLETED) {
            throw AppException.conflict("进货单未付款或已取消，不能录入包裹：" + status)
                    .code("admin.err.po.shipNotAllowed", status);
        }
        this.updatedAt = now;
    }

    /** 签收：写 {@code delivered_at}，只写一次。 */
    public void markDelivered(Instant at, PoDeliverySource source) {
        if (status != PurchaseOrderStatus.SHIPPED) {
            throw AppException.conflict("只有已发货的进货单可以标记送达：" + status)
                    .code("admin.err.po.deliverNotAllowed", status);
        }
        transitionTo(PurchaseOrderStatus.DELIVERED, at);
        this.deliveredAt = at;
        this.deliverySource = source;
    }

    /** 完成：写 {@code completed_at}。 */
    public void markCompleted(Instant at) {
        transitionTo(PurchaseOrderStatus.COMPLETED, at);
        this.completedAt = at;
    }

    public boolean isAutoDeliverDue(Instant now) {
        return status == PurchaseOrderStatus.SHIPPED && shippedAt != null
                && now.isAfter(shippedAt.plus(AUTO_DELIVER_AFTER));
    }

    public boolean isAutoCompleteDue(Instant now) {
        return status == PurchaseOrderStatus.DELIVERED && deliveredAt != null
                && now.isAfter(deliveredAt.plus(AUTO_COMPLETE_AFTER));
    }

    public void attachPaymentIntent(String token, Instant now) {
        this.paymentIntentToken = token;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getMerchantStoreId() {
        return merchantStoreId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public PoCancelReason getCancelReason() {
        return cancelReason;
    }

    public PoShippingMethod getShippingMethod() {
        return shippingMethod;
    }

    public long getGoodsAmount() {
        return goodsAmount;
    }

    /** 🔒 PII —— 不得写入日志。 */
    public AddressSnapshot shipTo() {
        return new AddressSnapshot(shipReceiverName, shipReceiverPhone, shipProvinsi,
                shipKotaKabupaten, shipKecamatan, shipAddressLine, shipKodePos);
    }

    public String getStoreNameSnapshot() {
        return storeNameSnapshot;
    }

    /** 🔒 PII —— 不得写入日志。 */
    public String getStorePhoneSnapshot() {
        return storePhoneSnapshot;
    }

    public Instant getPaymentDeadline() {
        return paymentDeadline;
    }

    public String getPaymentIntentToken() {
        return paymentIntentToken;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public PoDeliverySource getDeliverySource() {
        return deliverySource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 🔒 不打印任何 PII。 */
    @Override
    public String toString() {
        return "PurchaseOrder[" + publicToken + ", " + status + ", PII omitted]";
    }
}
