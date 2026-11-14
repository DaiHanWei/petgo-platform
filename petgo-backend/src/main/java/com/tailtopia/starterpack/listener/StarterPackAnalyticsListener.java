package com.tailtopia.starterpack.listener;

import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.starterpack.event.StarterPackFulfilledEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 服务端埋点 {@code starter_pack_purchased {tier, channel, purchase_index}}（V1.5.0 gift-pack Story 4-2 · AC6.5 · D-29）：
 * 发放事务提交后上报（照 {@code KtpUnlockAnalyticsListener}：不 {@code @Async}、不写库、属性只有枚举与数值）。
 */
@Component
public class StarterPackAnalyticsListener {

    public static final String EVENT_PURCHASED = "starter_pack_purchased";

    private final AnalyticsClient analytics;

    public StarterPackAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFulfilled(StarterPackFulfilledEvent e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tier", e.tier());
        p.put("channel", e.channel());
        p.put("purchase_index", e.purchaseIndex());
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_PURCHASED, p);
    }
}
