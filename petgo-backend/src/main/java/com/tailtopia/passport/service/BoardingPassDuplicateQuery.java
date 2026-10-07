package com.tailtopia.passport.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登机牌「付过两次」只读口（V1.3.2 Story 3.6 · AC5.2，后台异常页用）：合并后被 supersede 且仍保留
 * {@code unlocked_at} 的行（3.5 AC7 第 4 行）—— 退款候选。🛡 只给用户 id 与场所名，不给宠物名等 PII。
 */
@Service
public class BoardingPassDuplicateQuery {

    private static final String FROM = """
              FROM boarding_pass_unlocks b
              JOIN pet_profiles pp ON pp.id = b.pet_profile_id
              JOIN places pm ON pm.id = b.place_id
              LEFT JOIN places pk ON pk.id = pm.merged_into_id
             WHERE b.superseded_at IS NOT NULL AND b.unlocked_at IS NOT NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BoardingPassDuplicateQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Row> page(int page, int size) {
        return jdbc.query("SELECT b.id, pp.owner_id, pm.name AS from_place, pk.name AS to_place, b.unlocked_at, "
                + "b.superseded_at " + FROM + " ORDER BY b.superseded_at DESC, b.id DESC LIMIT :limit OFFSET :offset",
                Map.of("limit", size, "offset", (long) page * size),
                (rs, i) -> new Row(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("from_place"),
                        rs.getString("to_place"), instant(rs.getTimestamp("unlocked_at")),
                        instant(rs.getTimestamp("superseded_at"))));
    }

    @Transactional(readOnly = true)
    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) " + FROM, Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** @param unlockId 解锁行 id（仅供后台找关联购买，不进模板） */
    public record Row(long unlockId, long userId, String fromPlace, String toPlace, Instant unlockedAt,
            Instant supersededAt) {
    }
}
