package com.tailtopia.profile.slot.domain;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 名额购买行（V1.4.0 multi-pet Story 2.2 · AD-5 · 表 {@code pet_slot_purchases}，照 {@code KeepsakePurchase}）。
 * 资金流水：注销随 users 行匿名化口径不删。
 *
 * <p>{@link #unitPrice} / {@link #total} 是<b>下单快照</b>：后台改价只影响之后的新单，已生成的 PENDING 单按原价付。
 */
@Entity(name = "PetSlotPurchase")
@Table(name = "pet_slot_purchases")
public class PetSlotPurchase {

    /** 月租天数（每个名额每次 30 天）。 */
    public static final int RENTAL_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private PetSlotPurchaseKind kind;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "total", nullable = false)
    private long total;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", updatable = false, length = 16)
    private PayChannel channel;

    @Column(name = "payment_intent_id")
    private Long paymentIntentId;

    @Column(name = "idem_key", nullable = false, updatable = false, length = 160)
    private String idemKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PetSlotPurchaseStatus status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "rental_ids", columnDefinition = "bigint[]")
    private Long[] rentalIds;

    @Column(name = "days", nullable = false, updatable = false)
    private int days;

    @Column(name = "operator_admin_id", updatable = false)
    private Long operatorAdminId;

    @Column(name = "reason", updatable = false, length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected PetSlotPurchase() {
    }

    /**
     * 待付购买行（PawCoin 先落 PENDING 拿 id 作 {@code refId}、同一事务内再置 PAID；QRIS 带意图 id 等到账）。
     *
     * @param rentalIds 续租所选名额 id；新租为 null（发放后回填）
     */
    public static PetSlotPurchase pending(String publicToken, long userId, PetSlotPurchaseKind kind, int quantity,
            long unitPrice, long total, PayChannel channel, Long paymentIntentId, String idemKey,
            List<Long> rentalIds, Instant now) {
        PetSlotPurchase p = new PetSlotPurchase();
        p.publicToken = publicToken;
        p.userId = userId;
        p.kind = kind;
        p.quantity = quantity;
        p.unitPrice = unitPrice;
        p.total = total;
        p.channel = channel;
        p.paymentIntentId = paymentIntentId;
        p.idemKey = idemKey;
        p.rentalIds = rentalIds == null ? null : rentalIds.toArray(Long[]::new);
        p.days = RENTAL_DAYS;
        p.status = PetSlotPurchaseStatus.PENDING;
        p.createdAt = now;
        return p;
    }

    public void markPaid(Instant now) {
        settle(PetSlotPurchaseStatus.PAID, now);
    }

    /** 到账 / 扣币结算：PAID（已发放）/ ORPHAN_PAID（发放失败）/ OVERPAID（会超 5 个名额，不发放），记 {@code paid_at}。 */
    public void settle(PetSlotPurchaseStatus outcome, Instant now) {
        if (!outcome.isSettled()) {
            throw new IllegalArgumentException("非结算状态：" + outcome);
        }
        this.status = outcome;
        this.paidAt = now;
    }

    /** 付款失败 / 超时收尾（只由 PENDING 进入）。 */
    public void markStatus(PetSlotPurchaseStatus next) {
        this.status = next;
    }

    /** 回填实际发放的名额 id。 */
    public void setRentalIds(List<Long> ids) {
        this.rentalIds = ids == null ? null : ids.toArray(Long[]::new);
    }

    public List<Long> rentalIdList() {
        return rentalIds == null ? List.of() : List.of(rentalIds);
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getUserId() {
        return userId;
    }

    public PetSlotPurchaseKind getKind() {
        return kind;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPrice() {
        return unitPrice;
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

    public String getIdemKey() {
        return idemKey;
    }

    public PetSlotPurchaseStatus getStatus() {
        return status;
    }

    public int getDays() {
        return days;
    }

    public Long getOperatorAdminId() {
        return operatorAdminId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
