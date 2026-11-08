package com.tailtopia.shop.order.refund;

import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.pricing.RefundQuote;
import com.tailtopia.shop.returns.domain.CashDestination;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 取消退款单（v1.4.2 shop Story 4.1 AC1，架构 D-15；表 {@code shop_order_refunds}）。
 *
 * <p>🔴 金额只由 {@code ShopRefundCalculator} 算出后经 {@link #create} 落入，本实体<b>不做任何金额计算</b>（P-1），
 * 渠道费取 {@link PayoutChannel#fee()}（前端传费一律不采信）。
 *
 * <p>🔒 收款账号 / 户名 AES-GCM 加密落库（{@code EncryptedStringConverter}），读出即明文 ——
 * <b>只在视图层截取末 4 位</b>，不进日志、不进审计、不下发全号（P-7 / NFR4）。{@link #toString()} 不含它们。
 *
 * <p>{@code @Version}：用户选去向与财务标记并发时，后提交者整体回滚（409）。
 */
@Entity
@Table(name = "shop_order_refunds")
public class ShopOrderRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false, length = 16)
    private RefundScope scope;

    @Column(name = "order_line_id", updatable = false)
    private Long orderLineId;

    @Column(name = "line_qty", updatable = false)
    private Integer lineQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 32)
    private CancelReason reason;

    @Column(name = "goods_refund", nullable = false, updatable = false)
    private long goodsRefund;

    @Column(name = "shipping_refund", nullable = false, updatable = false)
    private long shippingRefund;

    @Column(name = "insurance_refund", nullable = false, updatable = false)
    private long insuranceRefund;

    @Column(name = "total_refund", nullable = false, updatable = false)
    private long totalRefund;

    @Column(name = "coin_refund", nullable = false, updatable = false)
    private long coinRefund;

    @Column(name = "cash_refund", nullable = false, updatable = false)
    private long cashRefund;

    @Enumerated(EnumType.STRING)
    @Column(name = "cash_destination", length = 16)
    private CashDestination cashDestination;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_channel", length = 16)
    private PayoutChannel payoutChannel;

    /** 🔒 PII：AES-GCM 加密列，读出即明文。不得写入日志 / 审计 / 出参。 */
    @Convert(converter = com.tailtopia.pay.refund.crypto.EncryptedStringConverter.class)
    @Column(name = "payout_account_enc", length = 255)
    private String payoutAccount;

    /** 🔒 PII：AES-GCM 加密列。 */
    @Convert(converter = com.tailtopia.pay.refund.crypto.EncryptedStringConverter.class)
    @Column(name = "payout_account_holder_enc", length = 255)
    private String payoutAccountHolder;

    @Column(name = "payout_fee", nullable = false)
    private long payoutFee;

    @Column(name = "compensation_amount", nullable = false)
    private long compensationAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ShopRefundStatus status;

    @Column(name = "destination_chosen_at")
    private Instant destinationChosenAt;

    @Column(name = "paid_out_at")
    private Instant paidOutAt;

    @Column(name = "payout_failed_at")
    private Instant payoutFailedAt;

    @Column(name = "payout_fail_note", length = 200)
    private String payoutFailNote;

    @Column(name = "destination_reminded_at")
    private Instant destinationRemindedAt;

    // ---------- V1.4.2 shop-admin Story 5.2 · AD-10：打款回填 / 失败原因 / 线下结案 ----------
    @Column(name = "payout_txn_no", length = 64)
    private String payoutTxnNo;
    @Column(name = "payout_proof_key", length = 255)
    private String payoutProofKey;
    @Column(name = "paid_out_by_admin_id")
    private Long paidOutByAdminId;
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_fail_reason", length = 24)
    private PayoutFailReason payoutFailReason;
    @Column(name = "payout_failed_by_admin_id")
    private Long payoutFailedByAdminId;
    @Column(name = "offline_closed_at")
    private Instant offlineClosedAt;
    @Column(name = "offline_closed_by_admin_id")
    private Long offlineClosedBy;
    @Column(name = "offline_close_note", length = 200)
    private String offlineCloseNote;

    @Column(name = "created_by_admin_id", updatable = false)
    private Long createdByAdminId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopOrderRefund() {
    }

    /**
     * 建单：金额原样取自计算器结果。现金段 &gt; 0 → {@code AWAITING_DESTINATION}；= 0 → 直接 {@code COMPLETED}
     * （币段在同一事务由 service 退回）。
     */
    public static ShopOrderRefund create(String publicToken, long orderId, RefundQuote q, Long createdByAdminId,
            Instant now) {
        if (q.coinRefund() + q.cashRefund() != q.totalRefund()
                || q.goodsRefund() + q.shippingRefund() + q.insuranceRefund() != q.totalRefund()
                || q.coinRefund() < 0 || q.cashRefund() < 0) {
            throw new IllegalStateException("退款单金额不自洽");
        }
        ShopOrderRefund r = new ShopOrderRefund();
        r.publicToken = publicToken;
        r.orderId = orderId;
        r.scope = q.scope();
        r.orderLineId = q.scope() == RefundScope.LINE ? q.lineId() : null;
        r.lineQty = q.scope() == RefundScope.LINE ? q.qty() : null;
        r.reason = q.reason();
        r.goodsRefund = q.goodsRefund();
        r.shippingRefund = q.shippingRefund();
        r.insuranceRefund = q.insuranceRefund();
        r.totalRefund = q.totalRefund();
        r.coinRefund = q.coinRefund();
        r.cashRefund = q.cashRefund();
        r.payoutFee = 0L;
        r.compensationAmount = 0L;
        r.status = q.cashRefund() > 0 ? ShopRefundStatus.AWAITING_DESTINATION : ShopRefundStatus.COMPLETED;
        r.createdByAdminId = createdByAdminId;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    /** 现金段选退为 PawCoin（AC4.4）：即时完成，记补偿额（只对现金段；无补偿传 0）。 */
    public void chooseToPawcoin(long compensation, Instant now) {
        requireChoosable();
        if (compensation < 0) {
            throw new IllegalStateException("补偿额不能为负");
        }
        this.cashDestination = CashDestination.TO_PAWCOIN;
        this.payoutChannel = null;
        this.payoutAccount = null;
        this.payoutAccountHolder = null;
        this.payoutFee = 0L;
        this.compensationAmount = compensation;
        this.destinationChosenAt = now;
        this.status = ShopRefundStatus.COMPLETED;
        this.updatedAt = now;
    }

    /** 现金段退到银行 / e-wallet（AC4.5）：渠道费按 {@link PayoutChannel#fee()}，转 {@code PAYOUT_PENDING} 等财务打款。 */
    public void chooseToBank(PayoutChannel channel, String account, String accountHolder, Instant now) {
        requireChoosable();
        this.cashDestination = CashDestination.TO_BANK;
        this.payoutChannel = channel;
        this.payoutAccount = account;
        this.payoutAccountHolder = accountHolder;
        this.payoutFee = channel.fee();
        this.compensationAmount = 0L;
        this.destinationChosenAt = now;
        this.status = ShopRefundStatus.PAYOUT_PENDING;
        this.updatedAt = now;
    }

    /**
     * 财务标记已打款（AC5.1）。
     *
     * @return true = 本次由 {@code PAYOUT_PENDING} 转 {@code COMPLETED}；false = 已完成（幂等，调用方不得重复发事件）
     */
    public boolean markPaidOut(Instant now) {
        if (status == ShopRefundStatus.COMPLETED && cashDestination == CashDestination.TO_BANK) {
            return false;
        }
        if (status != ShopRefundStatus.PAYOUT_PENDING) {
            throw AppException.conflict("该退款单不在待打款状态，当前：" + status)
                    .code("admin.err.orderRefund.notPayoutPending", status);
        }
        this.status = ShopRefundStatus.COMPLETED;
        this.paidOutAt = now;
        this.updatedAt = now;
        return true;
    }

    /** 财务标记打款失败（AC5.2）：{@code PAYOUT_PENDING → PAYOUT_FAILED}，用户可重选。备注 ≤ 200 字、不得含账户。 */
    public void markPayoutFailed(String note, Instant now) {
        if (status != ShopRefundStatus.PAYOUT_PENDING) {
            throw AppException.conflict("该退款单不在待打款状态，当前：" + status)
                    .code("admin.err.orderRefund.notPayoutPending", status);
        }
        this.status = ShopRefundStatus.PAYOUT_FAILED;
        this.payoutFailedAt = now;
        this.payoutFailNote = note;
        this.updatedAt = now;
    }

    /**
     * 财务标记已打款并回填流水号 / 截图（V1.4.2 shop-admin 5.2 · AD-10）。返回语义同 {@link #markPaidOut(Instant)}。
     */
    public boolean markPaidOut(Instant now, String txnNo, String proofKey, Long actorAdminId) {
        boolean changed = markPaidOut(now);
        if (changed) {
            this.payoutTxnNo = txnNo;
            this.payoutProofKey = proofKey;
            this.paidOutByAdminId = actorAdminId;
        }
        return changed;
    }

    /** 财务标记打款失败并记原因（V1.4.2 shop-admin 5.2 · AD-10）。 */
    public void markPayoutFailed(PayoutFailReason reason, String note, Instant now, Long actorAdminId) {
        markPayoutFailed(note, now);
        this.payoutFailReason = reason;
        this.payoutFailedByAdminId = actorAdminId;
    }

    /**
     * 注销用户线下处理结案（O-17 / O-26）：未完成的单 → {@code COMPLETED}，{@code cash_destination} 保持为空（留在注销用户页签）。
     * 已完成 → 409。
     */
    public void closeOffline(String note, Instant now, Long actorAdminId) {
        if (status == ShopRefundStatus.COMPLETED) {
            throw AppException.conflict("该退款单已完成").code("admin.err.v142.payout.alreadyCompleted");
        }
        this.status = ShopRefundStatus.COMPLETED;
        this.offlineClosedAt = now;
        this.offlineClosedBy = actorAdminId;
        this.offlineCloseNote = note;
        this.updatedAt = now;
    }

    public String getPayoutTxnNo() {
        return payoutTxnNo;
    }

    public String getPayoutProofKey() {
        return payoutProofKey;
    }

    public Long getPaidOutByAdminId() {
        return paidOutByAdminId;
    }

    public PayoutFailReason getPayoutFailReason() {
        return payoutFailReason;
    }

    public Long getPayoutFailedByAdminId() {
        return payoutFailedByAdminId;
    }

    public Instant getOfflineClosedAt() {
        return offlineClosedAt;
    }

    public Long getOfflineClosedBy() {
        return offlineClosedBy;
    }

    public String getOfflineCloseNote() {
        return offlineCloseNote;
    }

    /** 7 天未选去向的运营提醒已发（AC6.2，只提醒一次）。 */
    public void markDestinationReminded(Instant now) {
        this.destinationRemindedAt = now;
        this.updatedAt = now;
    }

    private void requireChoosable() {
        if (!status.canChooseDestination()) {
            throw AppException.refundDestinationLocked("该退款的退款方式已确定，不可再更改");
        }
    }

    /** 账号末 4 位（视图层用；无账号为 null）。🔒 只此一处截取，明文账号不出本实体。 */
    public String payoutAccountLast4() {
        if (payoutAccount == null || payoutAccount.isBlank()) {
            return null;
        }
        String a = payoutAccount.strip();
        return a.length() <= 4 ? a : a.substring(a.length() - 4);
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getOrderId() {
        return orderId;
    }

    public RefundScope getScope() {
        return scope;
    }

    public Long getOrderLineId() {
        return orderLineId;
    }

    public Integer getLineQty() {
        return lineQty;
    }

    public CancelReason getReason() {
        return reason;
    }

    public long getGoodsRefund() {
        return goodsRefund;
    }

    public long getShippingRefund() {
        return shippingRefund;
    }

    public long getInsuranceRefund() {
        return insuranceRefund;
    }

    public long getTotalRefund() {
        return totalRefund;
    }

    public long getCoinRefund() {
        return coinRefund;
    }

    public long getCashRefund() {
        return cashRefund;
    }

    public CashDestination getCashDestination() {
        return cashDestination;
    }

    public PayoutChannel getPayoutChannel() {
        return payoutChannel;
    }

    /** 🔒 PII 明文：只给财务打款路径（后台线）用，不得写日志。 */
    public String getPayoutAccount() {
        return payoutAccount;
    }

    /** 🔒 PII 明文：只给财务打款路径（后台线）用，不得写日志。 */
    public String getPayoutAccountHolder() {
        return payoutAccountHolder;
    }

    public long getPayoutFee() {
        return payoutFee;
    }

    public long getCompensationAmount() {
        return compensationAmount;
    }

    public ShopRefundStatus getStatus() {
        return status;
    }

    public Instant getDestinationChosenAt() {
        return destinationChosenAt;
    }

    public Instant getPaidOutAt() {
        return paidOutAt;
    }

    public Instant getPayoutFailedAt() {
        return payoutFailedAt;
    }

    public String getPayoutFailNote() {
        return payoutFailNote;
    }

    public Instant getDestinationRemindedAt() {
        return destinationRemindedAt;
    }

    public Long getCreatedByAdminId() {
        return createdByAdminId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 🔒 不含账号 / 户名。 */
    @Override
    public String toString() {
        return "ShopOrderRefund{token=" + publicToken + ", orderId=" + orderId + ", scope=" + scope
                + ", reason=" + reason + ", status=" + status + ", total=" + totalRefund + "}";
    }
}
