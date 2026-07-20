package com.tailtopia.consult.domain;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 兽医咨询订单（Story 3.1，{@code consult_orders} 支付成功才建、持久、进订单中心）。A-5 两表拆分的「付费后」半。
 *
 * <p>状态 {@code IN_PROGRESS→COMPLETED→REFUNDING→REFUNDED}（无 CANCELLED）。金额/分成/单价成交时快照
 * （后台 9-2 改价不影响历史/退款）。会话（IM）复用既有 {@code consult_sessions}（3-4 建），本表记
 * {@code session_started_at}/{@code session_ended_at}。时间戳 UTC，金额 bigint IDR。
 */
@Entity
@Table(name = "consult_orders")
public class ConsultOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_token", nullable = false, length = 32, updatable = false)
    private String orderToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "vet_id", nullable = false, updatable = false)
    private Long vetId;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ConsultOrderStatus status;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_channel", length = 16, updatable = false)
    private PayChannel payChannel;

    @Column(name = "payment_intent_id", updatable = false)
    private Long paymentIntentId;

    /** 兽医到手金额快照（如 Rp30,000 = 50k×60%）。 */
    @Column(name = "vet_payout", updatable = false)
    private Long vetPayout;

    /** 分成比例快照（如 60）。 */
    @Column(name = "vet_share_rate_snapshot", updatable = false)
    private Integer vetShareRateSnapshot;

    /** 单价快照（如 Rp50,000）。 */
    @Column(name = "unit_price_snapshot", updatable = false)
    private Long unitPriceSnapshot;

    /** A-2：退款驳回回落 COMPLETED 时置真。 */
    @Column(name = "refund_rejected", nullable = false)
    private boolean refundRejected;

    @Column(name = "session_started_at")
    private Instant sessionStartedAt;

    /** 关联的付费问诊会话 id（V88，付费建单后 markSessionStarted 回填）。迁移前订单为 null。 */
    @Column(name = "consult_session_id")
    private Long consultSessionId;

    @Column(name = "session_ended_at")
    private Instant sessionEndedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── Story 9.3（AB-8B）：建单重播快照 + 运营待核查标记（纯注记，不改业务状态）──
    @Column(name = "rebroadcast_count", nullable = false)
    private int rebroadcastCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_verify_status", length = 12)
    private ConsultOrderVerifyStatus adminVerifyStatus;

    @Column(name = "admin_verify_note", length = 255)
    private String adminVerifyNote;

    @Column(name = "admin_verify_by")
    private Long adminVerifyBy;

    @Column(name = "admin_verify_at")
    private Instant adminVerifyAt;

    protected ConsultOrder() {
    }

    /** 建单时从 request 快照重播次数（Story 9.3）。managed 实体，提交时 flush。 */
    public void snapshotRebroadcast(int count) {
        this.rebroadcastCount = count;
    }

    /** 运营标记待核查/已核查（Story 9.3）。纯注记，不改 {@link #status}。 */
    public void applyVerify(ConsultOrderVerifyStatus verifyStatus, String note, long adminId) {
        this.adminVerifyStatus = verifyStatus;
        this.adminVerifyNote = note;
        this.adminVerifyBy = adminId;
        this.adminVerifyAt = Instant.now();
    }

    /** 支付成功建单（3-4 调）：IN_PROGRESS + 成交快照 + paid_at。 */
    public static ConsultOrder inProgress(String orderToken, long userId, long vetId, long petProfileId,
            long amount, PayChannel payChannel, Long paymentIntentId, long vetPayout, int vetShareRate,
            long unitPrice, Instant paidAt) {
        ConsultOrder o = new ConsultOrder();
        o.orderToken = orderToken;
        o.userId = userId;
        o.vetId = vetId;
        o.petProfileId = petProfileId;
        o.status = ConsultOrderStatus.IN_PROGRESS;
        o.amount = amount;
        o.payChannel = payChannel;
        o.paymentIntentId = paymentIntentId;
        o.vetPayout = vetPayout;
        o.vetShareRateSnapshot = vetShareRate;
        o.unitPriceSnapshot = unitPrice;
        o.refundRejected = false;
        o.paidAt = paidAt;
        return o;
    }

    /** 支付成功建 IM 会话后记会话起始（Story 3.4）+ 回填会话 id（V88，历史到手金额按会话取）。 */
    public void markSessionStarted(Instant at, long consultSessionId) {
        this.sessionStartedAt = at;
        this.consultSessionId = consultSessionId;
    }

    /**
     * 会话完成（Story 3.7）：{@code IN_PROGRESS→COMPLETED} + 记 {@code session_ended_at}（会话终态时刻）。
     * <b>仅 IN_PROGRESS 可转</b>（幂等守卫：重复 CLOSED/重扫不重复完成；退款中/已退款不被误置）。返回是否发生转换。
     */
    public boolean markCompleted(Instant endedAt) {
        if (this.status != ConsultOrderStatus.IN_PROGRESS) {
            return false;
        }
        this.status = ConsultOrderStatus.COMPLETED;
        this.sessionEndedAt = endedAt;
        return true;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderToken() {
        return orderToken;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getVetId() {
        return vetId;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public ConsultOrderStatus getStatus() {
        return status;
    }

    public long getAmount() {
        return amount;
    }

    public PayChannel getPayChannel() {
        return payChannel;
    }

    public Long getPaymentIntentId() {
        return paymentIntentId;
    }

    public Long getVetPayout() {
        return vetPayout;
    }

    public Integer getVetShareRateSnapshot() {
        return vetShareRateSnapshot;
    }

    public Long getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public boolean isRefundRejected() {
        return refundRejected;
    }

    public Instant getSessionStartedAt() {
        return sessionStartedAt;
    }

    public Long getConsultSessionId() {
        return consultSessionId;
    }

    public Instant getSessionEndedAt() {
        return sessionEndedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getRebroadcastCount() {
        return rebroadcastCount;
    }

    public ConsultOrderVerifyStatus getAdminVerifyStatus() {
        return adminVerifyStatus;
    }

    public String getAdminVerifyNote() {
        return adminVerifyNote;
    }

    public Long getAdminVerifyBy() {
        return adminVerifyBy;
    }

    public Instant getAdminVerifyAt() {
        return adminVerifyAt;
    }
}
