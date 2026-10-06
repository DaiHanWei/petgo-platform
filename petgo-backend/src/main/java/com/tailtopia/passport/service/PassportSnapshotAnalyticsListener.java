package com.tailtopia.passport.service;

import com.tailtopia.passport.repository.PassportSnapshotRepository;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 护照快照解锁成功的服务端埋点（V1.3.2 Story 3.4 · AC8）。照 {@code TailsonalityUnlockAnalyticsListener}：
 * AFTER_COMMIT、不加 {@code @Async}、不写库、全程吞异常。属性只有章数与价格。
 *
 * <p>⚠️ 事件名 PRD 未给，暂定 {@link #EVENT_UNLOCKED}，待产品确认（Completion Notes）。
 */
@Component
public class PassportSnapshotAnalyticsListener {

    public static final String EVENT_UNLOCKED = "passport_snapshot_unlocked";

    private static final Logger log = LoggerFactory.getLogger(PassportSnapshotAnalyticsListener.class);

    private final AnalyticsClient analytics;
    private final PassportSnapshotRepository snapshots;

    public PassportSnapshotAnalyticsListener(AnalyticsClient analytics, PassportSnapshotRepository snapshots) {
        this.analytics = analytics;
        this.snapshots = snapshots;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnlocked(KeepsakeUnlockedEvent e) {
        if (e.sku() != KeepsakeSku.PASSPORT_SNAP) {
            return;
        }
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            snapshots.findById(e.refId()).ifPresent(s -> p.put("stamp_count", s.getStampCount()));
            p.put("price", e.priceIdr());
            analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_UNLOCKED, p);
        } catch (RuntimeException ex) {
            log.warn("passport snapshot analytics skipped refId={}", e.refId(), ex);
        }
    }
}
