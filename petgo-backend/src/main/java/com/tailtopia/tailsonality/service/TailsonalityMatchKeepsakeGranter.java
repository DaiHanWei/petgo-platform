package com.tailtopia.tailsonality.service;

import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeGranter;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.GrantSavepoint;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tailsonality 配型单独解锁的发放口（2026-10-09）：置 {@code tailsonality_results.match_unlocked_at}。
 * JDBC + 保存点的理由同 {@link TailsonalityKeepsakeGranter}。
 *
 * <p>完整解读已解锁（{@code unlocked_at} 非空）也算「已解锁」：配型本就含在里面，这笔到账记 DUPLICATE_PAID 交人工退款
 * —— 典型场景是先开了配型的 QRIS 没付，转头用 PawCoin 买了完整解读，再扫旧码付款。不佩戴徽章（徽章只跟完整解读走）。
 */
@Component
public class TailsonalityMatchKeepsakeGranter implements KeepsakeGranter {

    private static final Logger log = LoggerFactory.getLogger(TailsonalityMatchKeepsakeGranter.class);

    private final NamedParameterJdbcTemplate jdbc;

    public TailsonalityMatchKeepsakeGranter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public KeepsakeSku sku() {
        return KeepsakeSku.TS_MATCH;
    }

    @Override
    public GrantOutcome grant(long refId, long purchaseId) {
        try {
            return GrantSavepoint.run(jdbc.getJdbcTemplate(), () -> unlock(refId));
        } catch (RuntimeException e) {
            log.error("tailsonality match grant failed refId={}", refId, e);
            return GrantOutcome.REF_MISSING;
        }
    }

    private GrantOutcome unlock(long refId) {
        Map<String, Long> p = Map.of("id", refId);
        int updated = jdbc.update("UPDATE tailsonality_results SET match_unlocked_at = now()"
                + " WHERE id = :id AND match_unlocked_at IS NULL AND unlocked_at IS NULL", p);
        if (updated == 1) {
            return GrantOutcome.GRANTED;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM tailsonality_results WHERE id = :id", p, Integer.class);
        return exists != null && exists > 0 ? GrantOutcome.ALREADY_UNLOCKED : GrantOutcome.REF_MISSING;
    }
}
