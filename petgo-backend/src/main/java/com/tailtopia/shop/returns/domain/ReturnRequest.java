package com.tailtopia.shop.returns.domain;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * 退货申请（Story 5.1，FR-104A / C-12 / AD-5 / S-7 / S-8 / S-10）。
 *
 * <p>🔴 <b>一张申请承载 1..N 条 {@link ReturnLine}</b>，不是「一单一行」。
 *
 * <p>🔴 <b>状态机独立于订单状态机</b>（AD-5）。部分退款完成时订单主状态不变；
 * 仅当该订单全部行的退款均达 {@link ReturnStatus#REFUNDED}，系统才回写订单为
 * {@code REFUNDED}。
 *
 * <p>⚠️ <b>本类与 {@code pay/refund} 的 {@code RefundRequest} 是两个不同的东西</b>（AD-10）：
 * 前者是<b>实物流程</b>（勾了哪几行、货寄回来没有、质检过没过），后者是<b>资金流程</b>
 * （谁审批、打到哪个账户）。前者在质检通过后<b>驱动</b>后者。
 * 🔴 <b>不合并、不把实物字段塞进 RefundRequest</b>。
 */
@Entity
@Table(name = "return_requests")
public class ReturnRequest {

    /** 寄回时限（S-7）：超时未寄回则关闭。 */
    public static final Duration SHIPBACK_WINDOW = Duration.ofDays(7);

    /** 🔴 退款执行失败重试上限（S-8 ③）：超过即转人工，不无限重试。 */
    public static final int MAX_REFUND_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 🔴 对外标识，不可枚举（NFR-3）。 */
    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "shop_order_id", nullable = false, updatable = false)
    private Long shopOrderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ReturnStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 24)
    private ReturnType returnType;

    /** 🔴 由勾选范围自动得出，不给客服手工开关（C-12）。 */
    @Column(name = "is_full_return", nullable = false)
    private boolean fullReturn;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_ship_bearer", length = 16)
    private ShippingFeeBearer returnShipBearer;

    /** 去程运费是否退回。🔴 恒等于 {@link #fullReturn}（C-12）。 */
    @Column(name = "outbound_fee_refundable", nullable = false)
    private boolean outboundFeeRefundable;

    @Column(name = "reason_note", length = 500)
    private String reasonNote;

    /** 凭证图 key，逗号分隔，≤6 张。 */
    @Column(name = "evidence_keys", length = 1000)
    private String evidenceKeys;

    @Column(name = "reviewed_by")
    private Long reviewedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "shipback_deadline")
    private Instant shipbackDeadline;
    @Column(name = "shipback_carrier", length = 16)
    private String shipbackCarrier;
    @Column(name = "shipback_tracking_no", length = 64)
    private String shipbackTrackingNo;
    /** 用户垫付的实际运单金额；平台承担时在退款执行中一并返还（S-7）。 */
    @Column(name = "shipback_fee")
    private Long shipbackFee;

    @Column(name = "inspection_passed")
    private Boolean inspectionPassed;
    @Column(name = "inspection_note", length = 500)
    private String inspectionNote;
    @Column(name = "inspection_photo_keys", length = 1000)
    private String inspectionPhotoKeys;
    @Enumerated(EnumType.STRING)
    @Column(name = "reject_disposal", length = 16)
    private RejectDisposal rejectDisposal;
    @Column(name = "return_ship_back_tracking_no", length = 64)
    private String returnShipBackTrackingNo;

    @Column(name = "refund_amount")
    private Long refundAmount;
    @Column(name = "refund_coin")
    private Long refundCoin;
    @Column(name = "refund_cash")
    private Long refundCash;
    @Column(name = "compensation_premium", nullable = false)
    private long compensationPremium;
    @Column(name = "refund_attempts", nullable = false)
    private int refundAttempts;
    @Column(name = "refund_failure_note", length = 500)
    private String refundFailureNote;
    @Column(name = "refunded_at")
    private Instant refundedAt;

    // ---------- 现金段去向（5.5 / 5.8） ----------
    /**
     * 🔴 <b>只有现金段有去向可选。</b>PawCoin 段没有对应字段 —— 那不是遗漏：
     * FR-100A 规则 1 要求「PawCoin 段退成真钱」这条路在数据模型里就不存在。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cash_destination", length = 16)
    private CashDestination cashDestination;

    /** 🔴 零改动复用 {@code pay/refund} 的 PayoutChannel 费率表（与 FR-105 逐字一致）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_channel", length = 16)
    private com.tailtopia.pay.refund.domain.PayoutChannel payoutChannel;

    /** 🔒 PII：收款账号。加密列，绝不记日志、绝不进审计摘要。 */
    @jakarta.persistence.Convert(
            converter = com.tailtopia.pay.refund.crypto.EncryptedStringConverter.class)
    @Column(name = "payout_account", length = 255)
    private String payoutAccount;

    /** 🔒 PII：户名。加密列。 */
    @jakarta.persistence.Convert(
            converter = com.tailtopia.pay.refund.crypto.EncryptedStringConverter.class)
    @Column(name = "payout_account_holder", length = 255)
    private String payoutAccountHolder;

    @Column(name = "payout_channel_fee")
    private Long payoutChannelFee;

    /** 平台承担回程运费时按实际运单金额返还（S-7）。独立于 {@link #refundAmount}。 */
    @Column(name = "shipback_reimbursed", nullable = false)
    private long shipbackReimbursed;

    /** 现金段转 PawCoin 时的<b>激励</b>溢价（C-1）。🔴 与补偿溢价是两个独立配置项。 */
    @Column(name = "incentive_premium", nullable = false)
    private long incentivePremium;

    /** 🔴 S-8 ②：驳回 / 撤销后订单要回到这个状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_status_before", length = 32)
    private ShopOrderStatus orderStatusBefore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReturnRequest() {
    }

    public static ReturnRequest open(String publicToken, long shopOrderId, long userId,
            ReturnType returnType, boolean fullReturn, String reasonNote, String evidenceKeys,
            ShopOrderStatus orderStatusBefore) {
        ReturnRequest r = new ReturnRequest();
        r.publicToken = publicToken;
        r.shopOrderId = shopOrderId;
        r.userId = userId;
        r.status = ReturnStatus.PENDING_REVIEW;
        r.returnType = returnType;
        r.fullReturn = fullReturn;
        // 🔴 C-12：去程运费是否退【由勾选范围自动得出】，与 isFullReturn 同源。
        //    不给客服手工开关 —— 手工可调等于打开「凑单免运 → 退掉凑单商品」的套利口子。
        r.outboundFeeRefundable = fullReturn;
        // 回程运费归属由退货类型自动得出，同样不手工
        r.returnShipBearer = returnType.returnShipBearer();
        r.reasonNote = reasonNote;
        r.evidenceKeys = evidenceKeys;
        r.orderStatusBefore = orderStatusBefore;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        return r;
    }

    /** 🔴 状态迁移的唯一入口；合法性判定在 {@link ReturnStatus#canTransitionTo}。 */
    public void transitionTo(ReturnStatus next) {
        if (status == next) {
            return;     // 幂等
        }
        if (!status.canTransitionTo(next)) {
            throw AppException.conflict(
                    "退货申请状态不允许从 %s 变为 %s".formatted(status, next));
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    // ---------- 审核（5.3） ----------

    /**
     * 批准。
     *
     * <p>🔴 <b>「是否批准退货」与「运费由谁承担」分开记录</b>（AB-12A）——
     * 运费归属直接影响退款金额，合并成一个「批准」按钮就没人说得清那笔运费是怎么定的。
     * 这里运费归属由 {@link ReturnType} 自动得出，客服<b>不能改</b>。
     *
     * <p>拒收 / 发货前取消<b>跳过寄回与质检</b>，直接进入退款执行。
     */
    public void approve(long adminId) {
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
        if (returnType.skipsShipback()) {
            transitionTo(ReturnStatus.REFUNDING);
        } else {
            transitionTo(ReturnStatus.AWAIT_SHIPBACK);
            this.shipbackDeadline = Instant.now().plus(SHIPBACK_WINDOW);
        }
    }

    /** 驳回。🔴 理由必填并回告用户（复用 FR-52A）。 */
    public void reject(long adminId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("驳回必须填写理由（会回告用户）");
        }
        this.reviewedBy = adminId;
        this.reviewedAt = Instant.now();
        this.rejectReason = reason;
        transitionTo(ReturnStatus.REJECTED);
    }

    /** S-8 ④：用户主动撤销（仅待审核 / 待寄回两态）。 */
    public void withdraw() {
        if (status != ReturnStatus.PENDING_REVIEW && status != ReturnStatus.AWAIT_SHIPBACK) {
            throw AppException.conflict("当前状态不可撤销");
        }
        transitionTo(ReturnStatus.WITHDRAWN);
    }

    // ---------- 寄回（S-7） ----------

    /** 用户登记寄回运单。运费先由用户垫付；平台承担的情形在退款执行时按实际金额返还。 */
    public void registerShipback(String carrier, String trackingNo, Long fee) {
        if (trackingNo == null || trackingNo.isBlank()) {
            throw AppException.validation("请填写寄回的物流单号");
        }
        if (fee != null && fee < 0) {
            throw AppException.validation("运费不能为负");
        }
        this.shipbackCarrier = carrier;
        this.shipbackTrackingNo = trackingNo.trim();
        this.shipbackFee = fee;
        transitionTo(ReturnStatus.INSPECTING);
    }

    public boolean isShipbackOverdueAt(Instant now) {
        return status == ReturnStatus.AWAIT_SHIPBACK && shipbackDeadline != null
                && now.isAfter(shipbackDeadline);
    }

    public void closeForNoShipback() {
        transitionTo(ReturnStatus.CLOSED);
    }

    // ---------- 质检（5.4） ----------

    public void passInspection(String note, String photoKeys) {
        this.inspectionPassed = true;
        this.inspectionNote = note;
        this.inspectionPhotoKeys = photoKeys;
        transitionTo(ReturnStatus.REFUNDING);
    }

    /**
     * 质检不通过 → 驳回。
     *
     * <p>🔴 <b>S-10：处置方式必填，不留悬空</b> —— 用户的货已经寄出来了，
     * 「驳回」之后货在哪、要不要寄回去，一定要有答案。
     * 选「退回用户」时回寄运费<b>由平台承担</b>：是平台判定驳回，不应再让用户付。
     */
    public void failInspection(String note, String photoKeys, RejectDisposal disposal,
            String shipBackTrackingNo) {
        if (note == null || note.isBlank()) {
            throw AppException.validation("质检不通过必须填写原因");
        }
        if (disposal == null) {
            throw AppException.validation("质检不通过必须选择商品处置方式（退回用户 / 报损）");
        }
        this.inspectionPassed = false;
        this.inspectionNote = note;
        this.inspectionPhotoKeys = photoKeys;
        this.rejectDisposal = disposal;
        this.returnShipBackTrackingNo = shipBackTrackingNo;
        this.rejectReason = note;
        transitionTo(ReturnStatus.REJECTED);
    }

    // ---------- 退款执行（5.5） ----------

    /**
     * 用户选择现金段去向（Story 5.8）。
     *
     * <p>🔒 收款账号是 PII，落库加密；渠道费<b>由后端按 PayoutChannel 权威计算</b>，
     * 前端传来的费一律不采信（FR-NFR-5）。
     */
    public void chooseCashDestination(CashDestination destination,
            com.tailtopia.pay.refund.domain.PayoutChannel channel, String account,
            String accountHolder) {
        if (destination == null) {
            throw AppException.validation("请选择现金段的退回方式");
        }
        this.cashDestination = destination;
        if (destination == CashDestination.TO_BANK) {
            if (channel == null) {
                throw AppException.validation("请选择收款渠道");
            }
            if (account == null || account.isBlank()) {
                throw AppException.validation("请填写收款账号");
            }
            this.payoutChannel = channel;
            this.payoutAccount = account.trim();
            this.payoutAccountHolder = accountHolder;
            this.payoutChannelFee = channel.fee();
        } else {
            // 转 PawCoin 无渠道费；把银行相关字段一并清掉，免得留下互相矛盾的残值
            this.payoutChannel = null;
            this.payoutAccount = null;
            this.payoutAccountHolder = null;
            this.payoutChannelFee = 0L;
        }
        this.updatedAt = Instant.now();
    }

    public void recordShipbackReimbursement(long amount) {
        this.shipbackReimbursed = amount;
        this.updatedAt = Instant.now();
    }

    public void recordIncentivePremium(long amount) {
        this.incentivePremium = amount;
        this.updatedAt = Instant.now();
    }

    public void recordRefundPlan(long amount, long coin, long cash, long premium) {
        this.refundAmount = amount;
        this.refundCoin = coin;
        this.refundCash = cash;
        this.compensationPremium = premium;
        this.updatedAt = Instant.now();
    }

    public void markRefunded() {
        this.refundedAt = Instant.now();
        transitionTo(ReturnStatus.REFUNDED);
    }

    /** S-8 ③：失败可重试；超 {@link #MAX_REFUND_ATTEMPTS} 次转人工。 */
    public void markRefundFailed(String note) {
        this.refundAttempts += 1;
        this.refundFailureNote = note;
        transitionTo(ReturnStatus.REFUND_FAILED);
    }

    public boolean isRetryable() {
        return refundAttempts < MAX_REFUND_ATTEMPTS;
    }

    public void retryRefund() {
        if (!isRetryable()) {
            throw AppException.conflict(
                    "退款已失败 %d 次，请转人工处理".formatted(refundAttempts));
        }
        transitionTo(ReturnStatus.REFUNDING);
    }

    // ---------- getters ----------

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getShopOrderId() {
        return shopOrderId;
    }

    public Long getUserId() {
        return userId;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public ReturnType getReturnType() {
        return returnType;
    }

    public boolean isFullReturn() {
        return fullReturn;
    }

    public ShippingFeeBearer getReturnShipBearer() {
        return returnShipBearer;
    }

    public boolean isOutboundFeeRefundable() {
        return outboundFeeRefundable;
    }

    public String getReasonNote() {
        return reasonNote;
    }

    public String getEvidenceKeys() {
        return evidenceKeys;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public Instant getShipbackDeadline() {
        return shipbackDeadline;
    }

    public String getShipbackTrackingNo() {
        return shipbackTrackingNo;
    }

    public Long getShipbackFee() {
        return shipbackFee;
    }

    public Boolean getInspectionPassed() {
        return inspectionPassed;
    }

    public String getInspectionNote() {
        return inspectionNote;
    }

    public String getInspectionPhotoKeys() {
        return inspectionPhotoKeys;
    }

    public RejectDisposal getRejectDisposal() {
        return rejectDisposal;
    }

    public String getReturnShipBackTrackingNo() {
        return returnShipBackTrackingNo;
    }

    public Long getRefundAmount() {
        return refundAmount;
    }

    public Long getRefundCoin() {
        return refundCoin;
    }

    public Long getRefundCash() {
        return refundCash;
    }

    public long getCompensationPremium() {
        return compensationPremium;
    }

    public int getRefundAttempts() {
        return refundAttempts;
    }

    public String getRefundFailureNote() {
        return refundFailureNote;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public CashDestination getCashDestination() {
        return cashDestination;
    }

    public com.tailtopia.pay.refund.domain.PayoutChannel getPayoutChannel() {
        return payoutChannel;
    }

    /** 🔒 PII —— 调用方不得写入日志或审计摘要。 */
    public String getPayoutAccount() {
        return payoutAccount;
    }

    /** 🔒 PII。 */
    public String getPayoutAccountHolder() {
        return payoutAccountHolder;
    }

    public Long getPayoutChannelFee() {
        return payoutChannelFee;
    }

    public long getShipbackReimbursed() {
        return shipbackReimbursed;
    }

    public long getIncentivePremium() {
        return incentivePremium;
    }

    public ShopOrderStatus getOrderStatusBefore() {
        return orderStatusBefore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 🔒 不打印任何 PII。 */
    @Override
    public String toString() {
        return "ReturnRequest[" + publicToken + ", " + status + ", " + returnType + "]";
    }
}
