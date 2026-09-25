package com.tailtopia.profile.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.profile.event.IdHdPawcoinUnlockedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.LinkedHashMap;
import java.util.Map;
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
 */
@Component
public class KtpUnlockAnalyticsListener {

    public static final String EVENT_SUCCEEDED = "ktp_unlock_succeeded";
    public static final String EVENT_FAILED = "ktp_unlock_failed";

    private final AnalyticsClient analytics;

    public KtpUnlockAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    /** QRIS 到账（PawCoin 不建意图，走 {@link #onPawcoinUnlocked}）。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaid(PaymentIntentPaidEvent e) {
        if (e.purpose() != PaymentPurpose.ID_HD) {
            return;
        }
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_SUCCEEDED,
                props(e.channel() == null ? PayChannel.QRIS.name() : e.channel().name(), e.amount()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPawcoinUnlocked(IdHdPawcoinUnlockedEvent e) {
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_SUCCEEDED,
                props(PayChannel.PAWCOIN.name(), e.price()));
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
