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
import java.util.Locale;

/**
 * 包裹 / 运单（Story 4.1，S-2 一单多包的持久化依据；v1.4.2 shop Story 5.1 接入 Biteship）。
 *
 * <p>一个订单 1..N 个包裹，每包自带承运商 / 单号 / 承运成本 / 送达时刻。
 * 🔴 <b>订单转 {@code DELIVERED} 的条件是所有<i>活动</i>包裹都送达</b>（v1.4.2：被快递原因重发取代的
 * superseded 运单与已取消运单不计）；自动确认以最后一个包裹送达为起点。
 *
 * <p>两种来源（{@link ShipmentProvider}）：
 * <ul>
 *   <li>{@code MANUAL}：后台手工录入，{@link #ship} 建，录入即 {@code SHIPPED}，必有单号；</li>
 *   <li>{@code BITESHIP}：{@link #biteship} 建，初态 {@code CREATED}（未揽收、可能还没有运单号），
 *       由 Webhook 回查推进（{@link #markPicked} / {@link #markDelivered}），未揽收可 {@link #markCancelled}。</li>
 * </ul>
 *
 * <p>🔒 {@code trackingNo} / Biteship 订单 id <b>非 PII，可记日志</b>（NFR-5）；但同上下文的收件人姓名 / 电话 /
 * 详细地址严禁记录 —— 单号本身查不出人，单号加姓名就能。本类不持有任何收件人信息。
 */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_order_id", nullable = false, updatable = false)
    private Long shopOrderId;

    /** 手工：三家枚举名；Biteship：快递码大写（如 JNE / JNT / SICEPAT）。 */
    @Column(name = "carrier", nullable = false, length = 32)
    private String carrier;

    /** 手工必有；Biteship 运单 = {@code waybill_id}（建单时可能为空，稍后由回查补）。 */
    @Column(name = "tracking_no", length = 64)
    private String trackingNo;

    /** S-11 承运成本：运单实际金额。AB-13A 毛利口径依赖它。Biteship 取运单 {@code price}。 */
    @Column(name = "carrier_cost", nullable = false)
    private long carrierCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShipmentStatus status;

    /** 揽收 / 发出时刻。Biteship 运单在揽收前为空。 */
    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    // ---------- v1.4.2 shop Story 5.1：Biteship ----------

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private ShipmentProvider provider = ShipmentProvider.MANUAL;

    @Column(name = "biteship_order_id", length = 64)
    private String biteshipOrderId;

    @Column(name = "reference_id", length = 64)
    private String referenceId;

    @Column(name = "waybill_id", length = 64)
    private String waybillId;

    @Column(name = "tracking_id", length = 64)
    private String trackingId;

    /** Biteship 原始快递码（小写，如 {@code jne}）。 */
    @Column(name = "courier_code", length = 32)
    private String courierCode;

    @Column(name = "courier_service_code", length = 32)
    private String courierServiceCode;

    /** Biteship 原始状态（15 值之一或未知值原样）。 */
    @Column(name = "provider_status", length = 32)
    private String providerStatus;

    /** {@code provider_status} 的时刻：只前进不后退。 */
    @Column(name = "provider_status_at")
    private Instant providerStatusAt;

    /** 用户侧五段状态 key（D-12 映射）；手工运单恒 null。 */
    @Column(name = "user_status_key", length = 24)
    private String userStatusKey;

    /** Biteship 运单的「去承运商官网查询」链接（仅 https）。 */
    @Column(name = "tracking_url", length = 512)
    private String trackingUrl;

    /** 向 Biteship 申报的保价额（商品实付）；未勾选保价为 0。 */
    @Column(name = "insurance_amount", nullable = false)
    private long insuranceAmount;

    /**
     * Biteship 实际收取的保价费（V1.4.2 shop-admin Story 4.1 建列，V20261106_1020；6.1 由下运单响应
     * {@code courier.insurance.fee} 写入）。🔴 算钱（预估余额、物流成本）只用本列，不用 {@link #insuranceAmount}。
     */
    @Column(name = "insurance_cost", nullable = false)
    private long insuranceCost;

    /** 快递原因重发后被新运单取代的时刻（FR-172.8）；非空即非活动运单。 */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    /** 本地（运营 / 平台取消）发起 Biteship 取消的时刻；Biteship 回传 cancelled 时据此判为预期取消。 */
    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shipment() {
    }

    /** 手工运单（后台录入）：{@code provider=MANUAL}、{@code status=SHIPPED}、{@code shipped_at=now}。 */
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
        s.carrier = carrier.name();
        s.trackingNo = trackingNo.trim();
        s.carrierCost = carrierCost;
        s.provider = ShipmentProvider.MANUAL;
        s.status = ShipmentStatus.SHIPPED;
        s.shippedAt = Instant.now();
        s.createdAt = s.shippedAt;
        s.updatedAt = s.shippedAt;
        return s;
    }

    /**
     * Biteship 运单（v1.4.2 shop Story 5.1 AC4.4）：{@code status=CREATED}、{@code shipped_at=null}、
     * {@code user_status_key=awaiting_pickup}。
     *
     * @param carrierCode 快递码大写（写 {@code carrier} 列）
     * @param courierCode Biteship 原始快递码
     */
    public static Shipment biteship(long shopOrderId, String carrierCode, String courierCode, String serviceCode,
            String biteshipOrderId, String referenceId, String waybillId, String trackingId, long carrierCost,
            long insuranceAmount, Instant now) {
        if (biteshipOrderId == null || biteshipOrderId.isBlank()) {
            throw new IllegalArgumentException("biteshipOrderId required");
        }
        Shipment s = new Shipment();
        s.shopOrderId = shopOrderId;
        s.carrier = carrierCode == null ? "" : carrierCode.trim().toUpperCase(Locale.ROOT);
        s.courierCode = courierCode;
        s.courierServiceCode = serviceCode;
        s.biteshipOrderId = biteshipOrderId;
        s.referenceId = referenceId;
        s.waybillId = blankToNull(waybillId);
        s.trackingNo = s.waybillId;
        s.trackingId = blankToNull(trackingId);
        s.carrierCost = Math.max(0L, carrierCost);
        s.insuranceAmount = Math.max(0L, insuranceAmount);
        s.provider = ShipmentProvider.BITESHIP;
        s.status = ShipmentStatus.CREATED;
        s.userStatusKey = "awaiting_pickup";
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    /** 揽收：{@code CREATED → SHIPPED} 并写 {@code shipped_at}；已 SHIPPED / DELIVERED 幂等。 */
    public void markPicked(Instant at) {
        if (status == ShipmentStatus.SHIPPED || status == ShipmentStatus.DELIVERED) {
            return;
        }
        if (status != ShipmentStatus.CREATED) {
            throw AppException.conflict("运单状态不允许揽收：" + status);
        }
        this.status = ShipmentStatus.SHIPPED;
        if (this.shippedAt == null) {
            this.shippedAt = at;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * 标记本包裹送达（{@code CREATED / SHIPPED → DELIVERED}；从 CREATED 直达时 {@code shipped_at} 为空则同写 {@code at}）。
     *
     * <p>幂等：已送达的包裹重复标记不报错也<b>不改写送达时刻</b> ——
     * 时刻一旦被第二次标记推后，退货窗口就跟着往后挪，用户白得几天、平台多担几天。
     */
    public void markDelivered(Instant at) {
        if (status == ShipmentStatus.DELIVERED) {
            return;
        }
        if (status == ShipmentStatus.CANCELLED) {
            throw AppException.conflict("已取消的运单不能标记送达");
        }
        if (this.shippedAt == null) {
            this.shippedAt = at;
        }
        this.status = ShipmentStatus.DELIVERED;
        this.deliveredAt = at;
        this.updatedAt = Instant.now();
    }

    /** 取消运单：仅 {@code CREATED} 可取消（已揽收 Biteship 不允许取消），否则 409。已 CANCELLED 幂等。 */
    public void markCancelled(Instant at) {
        if (status == ShipmentStatus.CANCELLED) {
            return;
        }
        if (status != ShipmentStatus.CREATED) {
            throw AppException.conflict("运单已" + status + "，无法取消");
        }
        this.status = ShipmentStatus.CANCELLED;
        this.updatedAt = at;
    }

    /** 登记「本地已发起取消」（调 Biteship 取消之前）；只写一次。 */
    public void markCancelRequested(Instant at) {
        if (cancelRequestedAt == null) {
            this.cancelRequestedAt = at;
            this.updatedAt = at;
        }
    }

    /**
     * 复位「本地已发起取消」（v1.4.2 shop 5.2 复审 4）：Biteship 明确拒绝取消（{@code NotCancellable}）时调用——运单仍有效，
     * 否则之后 Biteship 推 {@code cancelled} 会被误判为预期取消、不告警。结果不确定（{@code Unknown}）时不复位。
     */
    public void clearCancelRequested(Instant at) {
        if (cancelRequestedAt != null) {
            this.cancelRequestedAt = null;
            this.updatedAt = at;
        }
    }

    /** 被快递原因重发的新运单取代（FR-172.8）：只写一次。 */
    public void supersede(Instant at) {
        if (supersededAt == null) {
            this.supersededAt = at;
            this.updatedAt = at;
        }
    }

    /** 活动运单 = 未被取代且未取消。订单推进、「全部送达」判定、物流出参只看活动运单。 */
    public boolean isActive() {
        return supersededAt == null && status != ShipmentStatus.CANCELLED;
    }

    public boolean isBiteship() {
        return provider == ShipmentProvider.BITESHIP;
    }

    /**
     * 记录 Biteship 原始状态。{@code provider_status_at} 只前进不后退（早于已存时刻的快照整体忽略，返回 false）。
     *
     * @param userKeyOrNull 映射出的用户侧状态；null 时不改 {@code user_status_key}
     * @return 是否接受了本次状态
     */
    public boolean applyProviderStatus(String raw, Instant at, String userKeyOrNull) {
        if (providerStatusAt != null && at != null && at.isBefore(providerStatusAt)) {
            return false;
        }
        this.providerStatus = raw;
        if (at != null) {
            this.providerStatusAt = at;
        }
        if (userKeyOrNull != null) {
            this.userStatusKey = userKeyOrNull;
        }
        this.updatedAt = Instant.now();
        return true;
    }

    /** 运单号（非空才覆盖，同步写 {@code tracking_no}）。 */
    public void updateWaybill(String waybill) {
        if (waybill != null && !waybill.isBlank()) {
            this.waybillId = waybill.trim();
            this.trackingNo = this.waybillId;
            this.updatedAt = Instant.now();
        }
    }

    /** Biteship 内部追踪 id（非空才覆盖）。 */
    public void updateTrackingId(String id) {
        if (id != null && !id.isBlank()) {
            this.trackingId = id.trim();
        }
    }

    /** 承运成本 = Biteship {@code price}（{@code order.price} 事件由此落地；用户已付运费不变）。 */
    public void updateCarrierCost(Long price) {
        if (price != null && price >= 0) {
            this.carrierCost = price;
        }
    }

    /** 承运商查询链接：仅 {@code https://} 开头才存，其余忽略。 */
    public void updateTrackingUrl(String link) {
        if (isHttps(link) && link.length() <= 512) {
            this.trackingUrl = link;
        }
    }

    public static boolean isHttps(String link) {
        return link != null && link.startsWith("https://");
    }

    /** 承运商展示名：三家之一取品牌写法，否则原样返回快递码。 */
    public String carrierDisplayName() {
        return Carrier.fromCode(carrier).map(Carrier::displayName).orElse(carrier);
    }

    /** 查询链接：手工运单取 {@link Carrier} 官网；Biteship 运单取 {@code tracking_url} 列（可空）。 */
    public String trackingUrl() {
        if (provider == ShipmentProvider.BITESHIP) {
            return trackingUrl;
        }
        return Carrier.fromCode(carrier).map(Carrier::trackingUrl).orElse(null);
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

    public String getCarrier() {
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

    public ShipmentProvider getProvider() {
        return provider;
    }

    public String getBiteshipOrderId() {
        return biteshipOrderId;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getWaybillId() {
        return waybillId;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public String getCourierCode() {
        return courierCode;
    }

    public String getCourierServiceCode() {
        return courierServiceCode;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public Instant getProviderStatusAt() {
        return providerStatusAt;
    }

    public String getUserStatusKey() {
        return userStatusKey;
    }

    public String getTrackingUrlColumn() {
        return trackingUrl;
    }

    public long getInsuranceAmount() {
        return insuranceAmount;
    }

    /** Biteship 实际收取的保价费（IDR）；未保价或未回写为 0。 */
    public long getInsuranceCost() {
        return insuranceCost;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 🔒 单号可打印（非 PII）；本类本就不持有姓名/电话/地址，无可泄露之物。 */
    @Override
    public String toString() {
        return "Shipment[" + carrier + " " + trackingNo + ", " + status + "]";
    }
}
