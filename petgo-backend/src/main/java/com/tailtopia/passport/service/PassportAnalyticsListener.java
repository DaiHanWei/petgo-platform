package com.tailtopia.passport.service;

import com.tailtopia.passport.event.PassportIssuedEvent;
import com.tailtopia.place.event.PlaceCheckedInEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 场所打卡 / 护照的服务端埋点（V1.3.2 Story 1.2 · AC7.2 · AD-19）。
 *
 * <p>照 {@code KtpUnlockAnalyticsListener}：AFTER_COMMIT（回滚的打卡 / 签发不上报）、不加 {@code @Async}
 * （capture 自身已异步）、不写库。事件名与属性键已登记 {@code AnalyticsEventGuard} 双白名单。
 *
 * <p>🛡 属性只有 token / 枚举 / 数值：<b>不带</b>宠物名、护照号、坐标。{@code place_id} 的<b>值是场所 token</b>
 * （沿用 App 端 {@code place_detail_viewed} 的键名约定），不是自增 id。
 */
@Component
public class PassportAnalyticsListener {

    public static final String EVENT_PLACE_CHECKIN = "place_checkin";
    public static final String EVENT_PASSPORT_ISSUED = "passport_issued";
    public static final String EVENT_PASSPORT_STAMPED = "passport_stamped";

    private final AnalyticsClient analytics;

    public PassportAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheckedIn(PlaceCheckedInEvent e) {
        String type = e.placeType() == null ? null : e.placeType().name();
        Map<String, Object> checkin = new LinkedHashMap<>();
        checkin.put("place_id", e.placeToken());
        if (type != null) {
            checkin.put("place_type", type);
        }
        checkin.put("is_new_stamp", e.isNewStamp());
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_PLACE_CHECKIN, checkin);
        if (e.isNewStamp()) {
            Map<String, Object> stamped = new LinkedHashMap<>();
            if (type != null) {
                stamped.put("place_type", type);
            }
            stamped.put("stamp_count", e.stampCount());
            analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_PASSPORT_STAMPED, stamped);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIssued(PassportIssuedEvent e) {
        analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_PASSPORT_ISSUED,
                Map.of("passport_source", e.source().name()));
    }
}
