package com.tailtopia.growth.listener;

import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.RefundSucceededEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 退款 → 按比例扣回消费返成长值（V1.4.0 ① Story 1.3 · 架构 AD-9）。
 *
 * <p>定位原消费流水（一律按<b>精确幂等键</b>，且必须属于同一用户）：有 {@code paymentIntentId} 且非电商 →
 * {@code SPEND:PI:{intentId}}；问诊纯币 → {@code SPEND:COIN:vet-consult-order:{orderId}}；电商 → {@code SPEND:SHOP:{orderId}}；
 * 其余用途按 {@code (ref_type = purpose, ref_id = bizRefId)} 找正向 {@code SPEND_*} 流水。找不到（当初未入账 / 档位 NONE）→ 不扣。
 * ⚠️ 不能对问诊 / 电商用 ref 反查：问诊 QRIS 消费的 ref_id 是支付意图 id，与问诊订单 id 同属 VET_CONSULT 命名空间，
 * 数字撞上就会扣到别人头上（code-review 2026-10-18）。
 * 扣回额 = {@code round(原入账 × refundAmount / originalAmount)}（HALF_UP）；同一原流水累计扣回 ≤ 原入账、幂等
 * （{@code REV:{原id}:REFUND:{refundKey}}）由 {@link GrowthLedgerService#reverse} 保证。送礼不退款，无扣回。
 */
@Component
public class GrowthRefundListener {

    private static final Logger log = LoggerFactory.getLogger(GrowthRefundListener.class);

    private final GrowthLedgerService ledger;

    public GrowthRefundListener(GrowthLedgerService ledger) {
        this.ledger = ledger;
    }

    @Async
    @TransactionalEventListener
    public void onRefunded(RefundSucceededEvent e) {
        if (e.originalAmount() <= 0 || e.refundAmount() <= 0) {
            log.warn("growth refund skipped (non-positive amount): refundKey={} userId={}", e.refundKey(), e.userId());
            return;
        }
        try {
            Optional<GrowthLedgerEntry> original = locate(e).filter(o -> o.getUserId() == e.userId());
            if (original.isEmpty()) {
                log.info("growth refund: no spend entry to reverse, refundKey={} userId={}", e.refundKey(), e.userId());
                return;
            }
            GrowthLedgerEntry o = original.get();
            int amount = proportional(o.getDelta(), e.refundAmount(), e.originalAmount());
            if (amount <= 0) {
                return;
            }
            ledger.reverse(new ReverseCommand(o.getIdemKey(), ReverseReason.REFUND,
                    GrowthIdemKeys.refundSource(e.refundKey()), amount));
        } catch (RuntimeException ex) {
            log.error("growth refund reverse failed: userId={} refundKey={} error={}", e.userId(), e.refundKey(),
                    ex.getClass().getSimpleName());
        }
    }

    private Optional<GrowthLedgerEntry> locate(RefundSucceededEvent e) {
        if (e.paymentIntentId() != null && e.purpose() != PaymentPurpose.SHOP_ORDER) {
            return ledger.findByIdemKey(GrowthIdemKeys.spendIntent(e.paymentIntentId()));
        }
        if (e.purpose() == null || e.bizRefId() == null) {
            return Optional.empty();
        }
        long bizId;
        try {
            bizId = Long.parseLong(e.bizRefId());
        } catch (NumberFormatException ex) {
            return ledger.findSpendByRef(e.purpose().name(), e.bizRefId());
        }
        return switch (e.purpose()) {
            case VET_CONSULT -> ledger.findByIdemKey(GrowthIdemKeys.spendConsultCoin(bizId));
            case SHOP_ORDER -> ledger.findByIdemKey(GrowthIdemKeys.spendShop(bizId));
            default -> ledger.findSpendByRef(e.purpose().name(), e.bizRefId());
        };
    }

    /** {@code round(delta × refund / original)}，HALF_UP。 */
    static int proportional(int delta, long refundAmount, long originalAmount) {
        return BigDecimal.valueOf(delta)
                .multiply(BigDecimal.valueOf(refundAmount))
                .divide(BigDecimal.valueOf(originalAmount), 0, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(delta))
                .intValue();
    }
}
