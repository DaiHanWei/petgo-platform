package com.tailtopia.passport.service;

import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeGranter;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.service.GrantSavepoint;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 登机牌的发放口（V1.3.2 Story 3.5 · AC6）：置 {@code boarding_pass_unlocks.unlocked_at}。JDBC + 保存点，不抛异常。
 *
 * <p>行已被合并 supersede（付款期间场所被并掉）→ 沿 {@code merged_into_id} 到最终场所，对 (宠物, 最终场所) 那一行发放
 * （无行先 upsert）。解析合并链<b>直接走 JDBC</b>，不调带 {@code @Transactional} 代理的服务 —— 代理里抛出会把外层到账事务标成
 * rollback-only。
 */
@Component
public class BoardingPassGranter implements KeepsakeGranter {

    private static final Logger log = LoggerFactory.getLogger(BoardingPassGranter.class);
    private static final int MAX_HOPS = 8;

    private final NamedParameterJdbcTemplate jdbc;
    private final PassportTokenGenerator tokens;

    public BoardingPassGranter(NamedParameterJdbcTemplate jdbc, PassportTokenGenerator tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    @Override
    public KeepsakeSku sku() {
        return KeepsakeSku.BOARDING_PASS;
    }

    @Override
    public GrantOutcome grant(long refId, long purchaseId) {
        try {
            return GrantSavepoint.run(jdbc.getJdbcTemplate(), () -> doGrant(refId));
        } catch (RuntimeException e) {
            log.error("boarding pass grant failed refId={}", refId, e);
            return GrantOutcome.REF_MISSING;
        }
    }

    private GrantOutcome doGrant(long refId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pet_profile_id, place_id, superseded_at FROM boarding_pass_unlocks WHERE id = :id",
                Map.of("id", refId));
        if (rows.isEmpty()) {
            return GrantOutcome.REF_MISSING; // 删档
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("superseded_at") == null) {
            int n = jdbc.update("UPDATE boarding_pass_unlocks SET unlocked_at = now() "
                    + "WHERE id = :id AND unlocked_at IS NULL", Map.of("id", refId));
            return n == 1 ? GrantOutcome.GRANTED : GrantOutcome.ALREADY_UNLOCKED;
        }
        long petId = ((Number) row.get("pet_profile_id")).longValue();
        long finalPlace = finalPlaceOf(((Number) row.get("place_id")).longValue());
        Map<String, Object> p = Map.of("pet", petId, "place", finalPlace, "token", tokens.generate());
        jdbc.update("INSERT INTO boarding_pass_unlocks (pet_profile_id, place_id, public_token) "
                + "VALUES (:pet, :place, :token) ON CONFLICT (pet_profile_id, place_id) DO NOTHING", p);
        int n = jdbc.update("UPDATE boarding_pass_unlocks SET unlocked_at = now() WHERE pet_profile_id = :pet "
                + "AND place_id = :place AND unlocked_at IS NULL AND superseded_at IS NULL", p);
        return n == 1 ? GrantOutcome.GRANTED : GrantOutcome.ALREADY_UNLOCKED;
    }

    private long finalPlaceOf(long placeId) {
        long cur = placeId;
        for (int i = 0; i < MAX_HOPS; i++) {
            List<Long> next = jdbc.queryForList(
                    "SELECT merged_into_id FROM places WHERE id = :id AND merged_into_id IS NOT NULL",
                    Map.of("id", cur), Long.class);
            if (next.isEmpty()) {
                return cur;
            }
            cur = next.get(0);
        }
        return cur;
    }
}
