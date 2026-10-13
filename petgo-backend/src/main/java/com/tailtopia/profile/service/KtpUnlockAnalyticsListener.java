package com.tailtopia.profile.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.domain.IdCardHdPurchase;
import com.tailtopia.profile.event.IdHdPawcoinUnlockedEvent;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * KTP（身份证高清图）付费漏斗的服务端埋点（2026-09-25）。
 *
 * <p>事件名与 App 端 {@code ktp_unlock_paywall_shown / ktp_unlock_started} 同一族，
 * 属性键也同名（{@code method} / {@code price_idr}），漏斗才能跨端拼起来。
 *
 * <p>🔴 <b>成功只在服务端报</b>：App 只在二维码弹窗开着时轮询到账，用户关掉弹窗后再付款
 * App 永远不知道 —— 客户端报「成功」会系统性少计收入。到账回调是唯一可信的点。
 *
 * <p>失败在这里报的是<b>支付侧</b>的结局：二维码超时未付（EXPIRED）、网关下单失败 / 拒付
 * （GATEWAY_DECLINED）、取消（USER_CANCELLED）。余额不足、请求没到服务端这两类由 App 报。
 *
 * <p>照 {@code ShopPaymentAnalyticsListener}：不加 {@code @Async}（capture 自身已异步）、不写库。
 * 🔒 属性只有枚举与数值，不带 token / 卡号 / 用户信息。
 *
 * <p>V1.3.4 ktp Story 1.8（AD-12）：{@code ktp_unlock_succeeded} 补 {@code card_type} / {@code card_variant}
 * （枚举名）。PawCoin 按事件里的卡 id 查卡；QRIS 由支付意图反查购买行 → 卡（与到账处理同一路径）。
 * 查不到卡（旧版单卡端点 / 无购买行）或查库异常 → 事件照发、只是不带这两个键；任何路径不抛异常。
 * {@code ktp_unlock_failed} 不补（T-20）。
 */
@Component
public class KtpUnlockAnalyticsListener {

    public static final String EVENT_SUCCEEDED = "ktp_unlock_succeeded";
    public static final String EVENT_FAILED = "ktp_unlock_failed";

    private static final Logger log = LoggerFactory.getLogger(KtpUnlockAnalyticsListener.class);

    private final AnalyticsClient analytics;
    private final IdCardRepository idCards;
    private final IdCardHdPurchaseRepository purchases;

    public KtpUnlockAnalyticsListener(AnalyticsClient analytics, IdCardRepository idCards,
            IdCardHdPurchaseRepository purchases) {
        this.analytics = analytics;
        this.idCards = idCards;
        this.purchases = purchases;
    }

    /** QRIS 到账（PawCoin 不建意图，走 {@link #onPawcoinUnlocked}）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaid(PaymentIntentPaidEvent e) {
        if (e.purpose() != PaymentPurpose.ID_HD) {
            return;
        }
        Map<String, Object> p = props(e.channel() == null ? PayChannel.QRIS.name() : e.channel().name(), e.amount());
        putCardStyle(p, safeCard(() -> purchases.findFirstByPaymentIntentId(e.intentId())
                .map(IdCardHdPurchase::getCardId)
                .flatMap(idCards::findById)));
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_SUCCEEDED, p);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPawcoinUnlocked(IdHdPawcoinUnlockedEvent e) {
        Map<String, Object> p = props(PayChannel.PAWCOIN.name(), e.price());
        if (e.cardId() != null) {
            putCardStyle(p, safeCard(() -> idCards.findById(e.cardId())));
        }
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_SUCCEEDED, p);
    }

    /** 查卡；任何异常都吞掉（埋点不能影响任何流程），只记一行不含业务数据的警告。 */
    private static Optional<IdCard> safeCard(Supplier<Optional<IdCard>> lookup) {
        try {
            return lookup.get();
        } catch (RuntimeException ex) {
            log.warn("ktp_unlock_succeeded 卡种反查失败，事件照发不带卡种: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 卡种 / 配色（枚举名）；配色为 null（非学生卡）不带键。 */
    private static void putCardStyle(Map<String, Object> p, Optional<IdCard> card) {
        card.ifPresent(c -> {
            if (c.getCardType() != null) {
                p.put("card_type", c.getCardType());
            }
            if (c.getCardVariant() != null) {
                p.put("card_variant", c.getCardVariant());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFailed(PaymentIntentFailedEvent e) {
        if (e.purpose() != PaymentPurpose.ID_HD || e.failureCategory() == null) {
            return; // 类别为 null 说明上游算错了，宁可不发也不发一条归不了类的
        }
        Map<String, Object> p = props(e.channel() == null ? null : e.channel().name(), e.amount());
        p.put("failure_reason", e.failureCategory().name());
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_FAILED, p);
    }

    private static Map<String, Object> props(String method, long priceIdr) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (method != null) {
            p.put("method", method);
        }
        p.put("price_idr", priceIdr);
        return p;
    }
}
