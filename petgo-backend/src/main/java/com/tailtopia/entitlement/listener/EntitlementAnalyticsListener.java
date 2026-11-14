package com.tailtopia.entitlement.listener;

import com.tailtopia.entitlement.event.EntitlementUsedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 服务端埋点 {@code entitlement_used {credit_type: ktp_hd|tailsonality|gift, source: pack|activity|admin}}
 * （V1.5.0 gift-pack Story 4-3 · D-29）：扣次数事务提交后上报（照 {@code KtpUnlockAnalyticsListener}：不 {@code @Async}、不写库）。
 */
@Component
public class EntitlementAnalyticsListener {

    public static final String EVENT_USED = "entitlement_used";

    private final AnalyticsClient analytics;

    public EntitlementAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUsed(EntitlementUsedEvent e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("credit_type", e.kind().name().toLowerCase(Locale.ROOT));
        if (e.source() != null) {
            p.put("source", e.source().name().toLowerCase(Locale.ROOT));
        }
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_USED, p);
    }
}
