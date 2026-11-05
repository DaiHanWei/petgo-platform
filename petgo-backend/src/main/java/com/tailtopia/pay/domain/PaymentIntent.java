package com.tailtopia.pay.domain;

import com.tailtopia.shared.error.AppException;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 支付意图（Story 1.1，建 {@code payment_intents} 表）。一切收费场景的资金意图根（FR-43 / FR-NFR-2）。
 *
 * <p>对外只暴露不可枚举 {@code public_token}（{@code CardTokenGenerator} 生成，绝不由顺序 id 派生）；
 * {@code gateway_ref} 唯一 = 回调/轮询双通道去重的库级权威兜底。状态迁移合法性集中在实体方法，
 * 非法迁移抛 {@link AppException#conflict}（→ ProblemDetail）。终态幂等由 service 层「已终态即返回」守卫。
 *
 * <p>本 story 的意图<b>不直接改余额</b>——{@link #markPaid()} 仅推进状态，到账/记账（双分录 + PawCoin）
 * 由 Story 1.2/1.3 经领域事件消费。并发（回调 + 轮询同时到）由 {@code @Version} 乐观锁裁决。
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, updatable = false)
    private String publicToken;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16, updatable = false)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private PayChannel channel;

    /** 金额（最小币种单位整型；IDR 无小数）。 */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "currency", nullable = false, length = 8, updatable = false)
    private String currency = "IDR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status = PaymentStatus.PENDING;

    /** 网关订单号（收款后回填，回调据此定位；唯一）。 */
    @Column(name = "gateway_ref", length = 128)
    private String gatewayRef;

    /** 网关原始快照（脱敏，绝不含签名/凭证）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gateway_meta")
    private Map<String, Object> gatewayMeta;

    /**
     * 付款窗过期时刻（V85）。QRIS 现金意图建单时按各业务付款窗填（充值 / 送礼 / 身份证高清 / AI 解锁 /
     * 纪念品 / 名额 60min，问诊 5min，商城跟订单剩余窗）；PawCoin 扣币即时成交的留 null=无时间过期。
     * 服务端权威：懒过期（statusOf/复用查询）+ 定时扫描据此置 EXPIRED。
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * 混合支付三列（Story 3.3 建列 · Story 3.8 首次写入）。
     *
     * <p>🔴 <b>非 MIXED 三列必须全 NULL；MIXED 三列必须非空且 coin + cash = amount</b> ——
     * 由 {@code ck_payment_intents_mixed_shape} 在库级强制。不变式放在 DB 而不是只放应用层，
     * 理由是「拆分金额对不上就是账对不平，而账不平只在退款时才暴露 —— 那时已经动过真钱了」。
     *
     * <p>🔴 {@code coinRatio} <b>只作展示与审计冗余，绝不参与计算</b>（AD-2）。
     */
    @Column(name = "coin_amount", updatable = false)
    private Long coinAmount;

    @Column(name = "cash_amount", updatable = false)
    private Long cashAmount;

    @Column(name = "coin_ratio", updatable = false)
    private java.math.BigDecimal coinRatio;

    /**
     * 网关码失效时刻（v1.4.2 shop Story 3.3，D-11）。电商意图出码成功时写入（出码 + 65 分钟，GemPay 固定），
     * 并同步给 {@link #expiresAt}。存量意图为空 → 判据按 {@code created_at + 65 分钟} 兜底，见
     * {@link #effectiveProviderExpiry()}。
     */
    @Column(name = "provider_expires_at")
    private Instant providerExpiresAt;

    /** 关单后到账标记（v1.4.2 shop Story 3.3，I1）：仅 SHOP_ORDER，库级 CHECK 强制。不新增意图状态。 */
    @Column(name = "paid_after_close", nullable = false)
    private boolean paidAfterClose;

    /** 所属电商订单（v1.4.2 shop Story 3.3）：一单多意图的关联；仅 SHOP_ORDER，库级 CHECK 强制。 */
    @Column(name = "shop_order_id")
    private Long shopOrderId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {
    }

    /** 建单：PENDING 起步。{@code publicToken} 由 service 用 {@code CardTokenGenerator} 现生成传入。 */
    public static PaymentIntent create(long userId, PaymentPurpose purpose, PayChannel channel,
            long amount, String currency, String publicToken) {
        return create(userId, purpose, channel, amount, currency, publicToken, null);
    }

    /**
     * 建单（含付款窗，V85）。{@code expiresAt} 非空即设过期时刻（PAWCOIN_TOPUP 传 now+60min）；
     * null = 无时间过期（其余 purpose，保持既有行为）。
     */
    public static PaymentIntent create(long userId, PaymentPurpose purpose, PayChannel channel,
            long amount, String currency, String publicToken, Instant expiresAt) {
        if (amount <= 0) {
            throw AppException.validation("支付金额必须为正");
        }
        PaymentIntent p = new PaymentIntent();
        p.userId = userId;
        p.purpose = purpose;
        p.channel = channel;
        p.amount = amount;
        p.currency = currency == null || currency.isBlank() ? "IDR" : currency;
        p.publicToken = publicToken;
        p.status = PaymentStatus.PENDING;
        p.expiresAt = expiresAt;
        return p;
    }

    /**
     * 建混合支付意图（Story 3.8，电商订单专用）。
     *
     * <p>🔴 {@code amount} 是<b>订单总额</b>，不是现金段：库级不变式要求 coin + cash = amount。
     * 网关那边只收 {@code cashAmount}（Coin 段站内扣），两者的差异必须靠这个不变式记下来 ——
     * 否则退款时无从知道当初有多少是币、多少是真钱（AD-2 整数累计法的分母）。
     */
    public static PaymentIntent createMixed(long userId, PaymentPurpose purpose, long amount,
            long coinAmount, long cashAmount, java.math.BigDecimal coinRatio, String currency,
            String publicToken, Instant expiresAt) {
        if (coinAmount < 0 || cashAmount < 0) {
            throw AppException.validation("拆分金额不能为负");
        }
        if (coinAmount + cashAmount != amount) {
            // 与 ck_payment_intents_mixed_shape 同一条不变式：应用层先挡一次，给得出更清楚的错
            throw AppException.validation("拆分金额与总额不符");
        }
        PaymentIntent p = create(userId, purpose, PayChannel.MIXED, amount, currency, publicToken,
                expiresAt);
        p.coinAmount = coinAmount;
        p.cashAmount = cashAmount;
        p.coinRatio = coinRatio;
        return p;
    }

    public Long getCoinAmount() {
        return coinAmount;
    }

    public Long getCashAmount() {
        return cashAmount;
    }

    public java.math.BigDecimal getCoinRatio() {
        return coinRatio;
    }

    /** 是否已过窗（仅对有 expiresAt 的意图有意义；null → 永不过期，恒 false）。 */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /** 收款创建成功后回填网关订单号 + 脱敏快照（仅 PENDING 可回填）。 */
    public void attachGatewayRef(String gatewayRef, Map<String, Object> meta) {
        requireStatus(PaymentStatus.PENDING, "仅待支付意图可绑定网关订单号");
        this.gatewayRef = gatewayRef;
        if (meta != null) {
            this.gatewayMeta = meta;
        }
    }

    /** PENDING → PAID（到账）。非 PENDING 抛冲突（终态幂等由 service 前置守卫拦下）。 */
    public void markPaid(Map<String, Object> meta) {
        requireStatus(PaymentStatus.PENDING, "支付意图不在可到账状态");
        this.status = PaymentStatus.PAID;
        if (meta != null) {
            this.gatewayMeta = meta;
        }
    }

    /** PENDING → FAILED（拒付/取消）。 */
    public void markFailed(Map<String, Object> meta) {
        requireStatus(PaymentStatus.PENDING, "支付意图不在可置失败状态");
        this.status = PaymentStatus.FAILED;
        if (meta != null) {
            this.gatewayMeta = meta;
        }
    }

    /** PENDING → EXPIRED（超时）。 */
    public void markExpired(Map<String, Object> meta) {
        requireStatus(PaymentStatus.PENDING, "支付意图不在可置过期状态");
        this.status = PaymentStatus.EXPIRED;
        if (meta != null) {
            this.gatewayMeta = meta;
        }
    }

    /**
     * GemPay 码固定有效期（出码 + 65 分钟，不可调）。仅用于存量意图（{@code provider_expires_at} 为空）的兜底：
     * 按 {@code created_at + 65 分钟} 估算，早于真实失效时刻不会出现（出码一定晚于建意图）。
     */
    public static final java.time.Duration PROVIDER_CODE_TTL = java.time.Duration.ofMinutes(65);

    /** 码失效时刻：{@code COALESCE(provider_expires_at, created_at + 65 分钟)}（v1.4.2 shop Story 3.3 B1 判据）。 */
    public Instant effectiveProviderExpiry() {
        if (providerExpiresAt != null) {
            return providerExpiresAt;
        }
        return createdAt == null ? null : createdAt.plus(PROVIDER_CODE_TTL);
    }

    /**
     * 出码成功后写码失效时刻（v1.4.2 shop Story 3.3 AC2.3）：{@code provider_expires_at} 与 {@code expires_at}
     * <b>同一值</b>（取网关返回之后的时刻，≥ GemPay 真实失效时刻，偏保守）。仅 PENDING。
     */
    public void markProviderIssued(Instant providerExpiresAt) {
        requireStatus(PaymentStatus.PENDING, "仅待支付意图可写码失效时刻");
        this.providerExpiresAt = providerExpiresAt;
        this.expiresAt = providerExpiresAt;
    }

    /** 绑定所属电商订单（v1.4.2 shop Story 3.3 AC2.2）：仅当为空时写；非 SHOP_ORDER 调用即抛。 */
    public void bindShopOrder(long orderId) {
        if (purpose != PaymentPurpose.SHOP_ORDER) {
            throw new IllegalStateException("仅电商意图可绑定订单");
        }
        if (this.shopOrderId == null) {
            this.shopOrderId = orderId;
        }
    }

    /**
     * 关单后到账（v1.4.2 shop Story 3.3 AC6.1，I1）：{@code FAILED | EXPIRED → PAID}，置 {@code paid_after_close}；
     * {@code gateway_ref} 为空时顺带写入。<b>只对 SHOP_ORDER</b>，其余业务线终态后到账仍按原逻辑忽略。
     *
     * @throws IllegalStateException 非电商意图，或当前状态不是 FAILED / EXPIRED
     */
    public void markPaidAfterClose(String gatewayRef, Map<String, Object> meta) {
        if (purpose != PaymentPurpose.SHOP_ORDER) {
            throw new IllegalStateException("仅电商意图可记关单后到账");
        }
        if (status != PaymentStatus.FAILED && status != PaymentStatus.EXPIRED) {
            throw new IllegalStateException("仅已失败 / 已过期的电商意图可记关单后到账");
        }
        this.status = PaymentStatus.PAID;
        this.paidAfterClose = true;
        if (this.gatewayRef == null && gatewayRef != null) {
            this.gatewayRef = gatewayRef;
        }
        if (meta != null) {
            this.gatewayMeta = meta;
        }
    }

    private void requireStatus(PaymentStatus expected, String message) {
        if (this.status != expected) {
            throw AppException.conflict(message);
        }
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

    public String getPublicToken() {
        return publicToken;
    }

    public Long getUserId() {
        return userId;
    }

    public PaymentPurpose getPurpose() {
        return purpose;
    }

    public PayChannel getChannel() {
        return channel;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getGatewayRef() {
        return gatewayRef;
    }

    public Map<String, Object> getGatewayMeta() {
        return gatewayMeta;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getProviderExpiresAt() {
        return providerExpiresAt;
    }

    public boolean isPaidAfterClose() {
        return paidAfterClose;
    }

    public Long getShopOrderId() {
        return shopOrderId;
    }
}
