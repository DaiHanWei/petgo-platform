package com.tailtopia.passport.service;

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
 * 护照快照的发放口（V1.3.2 Story 3.4 · AC4）：置 {@code passport_snapshots.paid_at}。
 *
 * <p>按<b>冻结内容</b>发放：付款期间盖了新章，买到的仍是发起那一刻的版本（D-4）；当前护照因此仍带水印。
 * JDBC + 保存点（{@link GrantSavepoint}），不抛异常。
 */
@Component
public class PassportSnapshotGranter implements KeepsakeGranter {

    private static final Logger log = LoggerFactory.getLogger(PassportSnapshotGranter.class);

    private final NamedParameterJdbcTemplate jdbc;

    public PassportSnapshotGranter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public KeepsakeSku sku() {
        return KeepsakeSku.PASSPORT_SNAP;
    }

    @Override
    public GrantOutcome grant(long refId, long purchaseId) {
        try {
            return GrantSavepoint.run(jdbc.getJdbcTemplate(), () -> {
                Map<String, Long> p = Map.of("id", refId);
                int updated = jdbc.update(
                        "UPDATE passport_snapshots SET paid_at = now() WHERE id = :id AND paid_at IS NULL", p);
                if (updated == 1) {
                    return GrantOutcome.GRANTED;
                }
                Integer exists = jdbc.queryForObject(
                        "SELECT count(*) FROM passport_snapshots WHERE id = :id", p, Integer.class);
                return exists != null && exists > 0 ? GrantOutcome.ALREADY_UNLOCKED : GrantOutcome.REF_MISSING;
            });
        } catch (RuntimeException e) {
            log.error("passport snapshot grant failed refId={}", refId, e);
            return GrantOutcome.REF_MISSING;
        }
    }
}
