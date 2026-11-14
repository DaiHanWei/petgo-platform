package com.tailtopia.profile.service;

import com.tailtopia.profile.event.PetSpeciesChangedEvent;
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
 * 埋点 {@code pet_type_changed}（V1.5.0 pet-species Story 1.2 · §8）：用户改 / 运营代改提交后上报。
 *
 * <p>🔴 <b>不写库</b>（只上报），故不需要 REQUIRES_NEW。不加 {@code @Async}（capture 自身已异步）。
 * 失败吞掉只记 warn（不带宠物名 / 品种 / 原因）。
 */
@Component
public class PetSpeciesAnalyticsListener {

    public static final String EVENT_PET_TYPE_CHANGED = "pet_type_changed";

    private static final Logger log = LoggerFactory.getLogger(PetSpeciesAnalyticsListener.class);

    private final AnalyticsClient analytics;

    public PetSpeciesAnalyticsListener(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(PetSpeciesChangedEvent e) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("from_species", e.fromSpecies());
            p.put("to_species", e.toSpecies());
            p.put("group_changed", e.groupChanged());
            p.put("source", e.source());
            analytics.capture(AnalyticsDistinctId.of(e.ownerId()), EVENT_PET_TYPE_CHANGED, p);
        } catch (RuntimeException ex) {
            log.warn("pet_type_changed capture failed: {}", ex.getClass().getSimpleName());
        }
    }
}
