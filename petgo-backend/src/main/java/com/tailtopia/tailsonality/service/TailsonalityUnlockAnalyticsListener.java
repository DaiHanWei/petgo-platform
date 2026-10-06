package com.tailtopia.tailsonality.service;

import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tailsonality 解锁成功的服务端埋点（V1.3.2 Story 3.2 · AC3）。
 *
 * <p>🔴 成功只在服务端报（同 {@code KtpUnlockAnalyticsListener}）：用户关掉二维码面板后再付款，App 不知道。
 * 照那边写法：不加 {@code @Async}、不写库。属性只有代号 / 数值（AD-19：不带宠物名、品种、token）。
 */
@Component
public class TailsonalityUnlockAnalyticsListener {

    public static final String EVENT_UNLOCKED = "tailsonality_unlocked";

    private static final Logger log = LoggerFactory.getLogger(TailsonalityUnlockAnalyticsListener.class);

    private final AnalyticsClient analytics;
    private final TailsonalityResultRepository results;
    private final TailsonalityResultService resultService;

    public TailsonalityUnlockAnalyticsListener(AnalyticsClient analytics, TailsonalityResultRepository results,
            TailsonalityResultService resultService) {
        this.analytics = analytics;
        this.results = results;
        this.resultService = resultService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnlocked(KeepsakeUnlockedEvent e) {
        if (e.sku() != KeepsakeSku.TAILSONALITY) {
            return;
        }
        // AFTER_COMMIT 里抛出会冒到提交方（用户看到 500，钱却已成交）—— 埋点一律吞掉。
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            TailsonalityResult row = results.findById(e.refId()).orElse(null);
            if (row != null) {
                p.put("role_code", row.code().full());
            }
            p.put("price", e.priceIdr());
            if (row != null) {
                p.put("result_index", resultService.indexOf(row.getPetProfileId(), row.getId()));
            }
            analytics.capture(AnalyticsDistinctId.of(e.userId()), EVENT_UNLOCKED, p);
        } catch (RuntimeException ex) {
            log.warn("tailsonality unlock analytics skipped refId={}", e.refId(), ex);
        }
    }
}
