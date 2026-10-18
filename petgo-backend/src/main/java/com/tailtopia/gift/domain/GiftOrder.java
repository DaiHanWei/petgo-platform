package com.tailtopia.gift.domain;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 送礼订单（V1.4.0 gift Story 1.1 · AD-2 · 表 {@code gift_orders}）。资金流水：删档 / 注销都保留。
 *
 * <p>礼物名 / 图标 / 单价与宠物名都是<b>下单时快照</b>：目录改名、改价、下线与宠物删除都不影响已下单
 * （{@link #petName} 是对 AD-2 的补充：宠物删除后退回通知与送礼记录仍要显示名字）。
 *
 * <p>🔴 状态只经 {@code GiftDeliveryService}（DELIVERED / UNDELIVERED_REFUND_PENDING，条件更新）、
 * 两个支付监听与发起服务的「幂等重放发现旧意图已过窗」（{@link #markExpired} / {@link #markFailed}）改变；
 * 退回相关状态由 Story 1.2 的退回服务改变。
 *
 * <p>🔴 {@code @DynamicUpdate}：实体 UPDATE 只写脏列。{@code pet_id} 由库级 {@code ON DELETE SET NULL} 维护、
 * {@code delivered_at} / {@code undelivered_reason} 由交付的条件更新（原生 SQL）写入 —— 全列 UPDATE 会把装载时的旧值写回：
 * 失败监听 / 重放置 EXPIRED 期间宠物被并发删除时，旧 {@code pet_id} 写回撞外键，整个事务（含过期扫描整批）回滚。
 */
@Entity(name = "GiftOrder")
@DynamicUpdate
@Table(name = "gift_orders")
public class GiftOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private long senderId;

    /** 删宠物时库级 {@code ON DELETE SET NULL}。 */
    @Column(name = "pet_id")
    private Long petId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private long ownerId;

    @Column(name = "pet_name", nullable = false, updatable = false, length = 20)
    private String petName;

    @Column(name = "gift_id", nullable = false, updatable = false)
    private long giftId;

    @Column(name = "gift_name_en", nullable = false, updatable = false, length = 40)
    private String giftNameEn;

    @Column(name = "gift_name_id", nullable = false, updatable = false, length = 40)
    private String giftNameId;

    @Column(name = "gift_icon_url", nullable = false, updatable = false, length = 1024)
    private String giftIconUrl;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "total", nullable = false, updatable = false)
    private long total;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private PayChannel channel;

    @Column(name = "payment_intent_id")
    private Long paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private GiftOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "undelivered_reason", length = 24)
    private GiftUndeliveredReason undeliveredReason;

    @Column(name = "refund_attempts", nullable = false)
    private int refundAttempts;

    /** 最近一次退回失败的异常类名（只存类名，Story 1.2 写）。 */
    @Column(name = "refund_error", length = 64)
    private String refundError;

    @Convert(converter = GiftSourceConverter.class)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private GiftSource source;

    @Column(name = "idem_key", nullable = false, updatable = false, length = 96)
    private String idemKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected GiftOrder() {
    }

    /**
     * 新建 PENDING 订单（PawCoin 与 QRIS 同形；QRIS 的意图 id 建意图后经 {@link #attachPaymentIntent} 回填）。
     * {@code total = unitPrice × quantity} 在此算定（库级 CHECK 同口径）。
     */
    public static GiftOrder pending(String publicToken, long senderId, long petId, long ownerId, String petName,
            GiftCatalogItem gift, int quantity, PayChannel channel, GiftSource source, String idemKey, Instant now) {
        GiftOrder o = new GiftOrder();
        o.publicToken = publicToken;
        o.senderId = senderId;
        o.petId = petId;
        o.ownerId = ownerId;
        o.petName = petName;
        o.giftId = gift.getId();
        o.giftNameEn = gift.getNameEn();
        o.giftNameId = gift.getNameId();
        o.giftIconUrl = gift.getIconUrl();
        o.unitPrice = gift.getPrice();
        o.quantity = quantity;
        o.total = gift.getPrice() * quantity;
        o.channel = channel;
        o.source = source;
        o.idemKey = idemKey;
        o.status = GiftOrderStatus.PENDING;
        o.refundAttempts = 0;
        o.createdAt = now;
        return o;
    }

    /** QRIS：建意图后回填（只回填一次；一笔订单只对应一笔意图）。 */
    public void attachPaymentIntent(long intentId) {
        if (this.paymentIntentId != null && this.paymentIntentId != intentId) {
            throw new IllegalStateException("订单已绑定其他支付意图");
        }
        this.paymentIntentId = intentId;
    }

    /** PENDING → EXPIRED（付款窗超时；非 PENDING 返回 false 不动）。 */
    public boolean markExpired() {
        return moveFromPending(GiftOrderStatus.EXPIRED);
    }

    /** PENDING → FAILED（网关拒付 / 用户取消 / 网关下单失败；非 PENDING 返回 false 不动）。 */
    public boolean markFailed() {
        return moveFromPending(GiftOrderStatus.FAILED);
    }

    private boolean moveFromPending(GiftOrderStatus to) {
        if (status != GiftOrderStatus.PENDING) {
            return false;
        }
        status = to;
        return true;
    }

    public boolean isPending() {
        return status == GiftOrderStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public long getSenderId() {
        return senderId;
    }

    public Long getPetId() {
        return petId;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public String getPetName() {
        return petName;
    }

    public long getGiftId() {
        return giftId;
    }

    public String getGiftNameEn() {
        return giftNameEn;
    }

    public String getGiftNameId() {
        return giftNameId;
    }

    public String getGiftIconUrl() {
        return giftIconUrl;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getTotal() {
        return total;
    }

    public PayChannel getChannel() {
        return channel;
    }

    public Long getPaymentIntentId() {
        return paymentIntentId;
    }

    public GiftOrderStatus getStatus() {
        return status;
    }

    public GiftUndeliveredReason getUndeliveredReason() {
        return undeliveredReason;
    }

    public int getRefundAttempts() {
        return refundAttempts;
    }

    public String getRefundError() {
        return refundError;
    }

    public GiftSource getSource() {
        return source;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
