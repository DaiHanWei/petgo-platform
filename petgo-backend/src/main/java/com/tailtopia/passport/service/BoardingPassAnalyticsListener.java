package com.tailtopia.passport.service;

import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 登机牌解锁成功的服务端埋点（V1.3.2 Story 3.5 · AC10）：属性只有 {@code place_type} 与 {@code price}
 * （不带场所 id / 名称）。AFTER_COMMIT、不写库、全程吞异常。
 *
 * <p>⚠️ 事件名 PRD 未给，暂定 {@link #EVENT_UNLOCKED}，待产品确认。
 */
@Component
public class BoardingPassAnalyticsListener {

    public static final String EVENT_UNLOCKED = "boarding_pass_unlocked";

    private static final Logger log = LoggerFactory.getLogger(BoardingPassAnalyticsListener.class);

    private final AnalyticsClient analytics;
    private final NamedParameterJdbcTemplate jdbc;

    public BoardingPassAnalyticsListener(AnalyticsClient analytics, NamedParameterJdbcTemplate jdbc) {
        this.analytics = analytics;
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnlocked(KeepsakeUnlockedEvent e) {
        if (e.sku() != KeepsakeSku.BOARDING_PASS) {
            return;
        }
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            List<String> type = jdbc.queryForList("SELECT p.place_type FROM boarding_pass_unlocks b "
                    + "JOIN places p ON p.id = b.place_id WHERE b.id = :id", Map.of("id", e.refId()), String.class);
            if (!type.isEmpty() && type.get(0) != null) {
                p.put("place_type", type.get(0));
            }
            p.put("price", e.priceIdr());
            analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_UNLOCKED, p);
        } catch (RuntimeException ex) {
            log.warn("boarding pass analytics skipped refId={}", e.refId(), ex);
        }
    }
}
