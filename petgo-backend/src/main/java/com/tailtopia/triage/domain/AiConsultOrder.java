package com.tailtopia.triage.domain;

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
 * AI 解锁已结算订单（Story 2.3，{@code ai_consult_orders} 独立命名空间）。仅付费路径建订单：
 * <ul>
 *   <li><b>PawCoin</b>（同步）：直接 {@link AiConsultOrderStatus#COMPLETED}，{@code paymentIntentToken=null}、
 *       {@code paidAt=now}（见 {@link #completedPawCoin}）。</li>
 *   <li><b>现金 QRIS</b>（异步）：先 {@link AiConsultOrderStatus#PENDING_PAYMENT} + 存 {@code paymentIntentToken}
 *       作 intent↔triage 关联锚（见 {@link #pendingCash}）；Midtrans 到账后 {@link #markCompleted}。</li>
 * </ul>
 * 免费额度解锁<b>不建订单</b>（{@code triage_tasks.unlock_source=FREE_QUOTA} 即记录）。{@code amount} 落成交价快照。
 */
@Entity
@Table(name = "ai_consult_orders")
public class AiConsultOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_token", nullable = false, length = 32, updatable = false)
    private String orderToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "triage_task_id", nullable = false, updatable = false)
    private Long triageTaskId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_channel", nullable = false, length = 16, updatable = false)
    private PayChannel payChannel;

    /** 现金路径关联 payment_intents 的 publicToken；PawCoin 站内扣为 null。 */
    @Column(name = "payment_intent_token", length = 32, updatable = false)
    private String paymentIntentToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AiConsultOrderStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiConsultOrder() {
    }

    /** PawCoin 同步解锁：直接 COMPLETED（无 intent，paidAt=now）。 */
    public static AiConsultOrder completedPawCoin(String orderToken, long userId, long triageTaskId, long amount) {
        AiConsultOrder o = new AiConsultOrder();
        o.orderToken = orderToken;
        o.userId = userId;
        o.triageTaskId = triageTaskId;
        o.amount = amount;
        o.payChannel = PayChannel.PAWCOIN;
        o.paymentIntentToken = null;
        o.status = AiConsultOrderStatus.COMPLETED;
        o.paidAt = Instant.now();
        return o;
    }

    /** 现金异步解锁：PENDING_PAYMENT + 存 intent token 作关联锚。 */
    public static AiConsultOrder pendingCash(String orderToken, long userId, long triageTaskId, long amount,
            PayChannel channel, String paymentIntentToken) {
        AiConsultOrder o = new AiConsultOrder();
        o.orderToken = orderToken;
        o.userId = userId;
        o.triageTaskId = triageTaskId;
        o.amount = amount;
        o.payChannel = channel;
        o.paymentIntentToken = paymentIntentToken;
        o.status = AiConsultOrderStatus.PENDING_PAYMENT;
        return o;
    }

    /** 现金到账：置 COMPLETED + paidAt。 */
    public void markCompleted(Instant paidAt) {
        this.status = AiConsultOrderStatus.COMPLETED;
        this.paidAt = paidAt;
    }

    /** 对账异常（到账但 triage 缺失/已被其它路径解锁/金额不符等）。 */
    public void markAbnormal() {
        this.status = AiConsultOrderStatus.ABNORMAL;
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

    public Long getTriageTaskId() {
        return triageTaskId;
    }

    public long getAmount() {
        return amount;
    }

    public PayChannel getPayChannel() {
        return payChannel;
    }

    public String getPaymentIntentToken() {
        return paymentIntentToken;
    }

    public AiConsultOrderStatus getStatus() {
        return status;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
