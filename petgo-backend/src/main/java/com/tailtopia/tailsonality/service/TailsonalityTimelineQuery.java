package com.tailtopia.tailsonality.service;

import com.tailtopia.tailsonality.dto.TailsonalityTimelineView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Diary 时间线的 Tailsonality 只读口（V1.3.2 Story 3.3 · AC6 · AD-9），写法照 {@code PlaceCheckinTimelineQuery}：
 * profile 经它取数，<b>不直接注入</b> tailsonality 的仓库。
 *
 * <p>只取 {@code unlocked_at IS NOT NULL} 的结果；多次解锁多条并存。UTC 日界用半开区间比较
 * {@code unlocked_at}，不在 SQL 里做时区敏感的 date 转换。
 */
@Service
public class TailsonalityTimelineQuery {

    private static final String SELECT = """
            SELECT id, unlocked_at, public_token, type_code, energy, created_at
              FROM tailsonality_results
             WHERE pet_profile_id = :petId AND unlocked_at IS NOT NULL
            """;

    private static final RowMapper<TailsonalityTimelineView> ROW = (rs, i) -> new TailsonalityTimelineView(
            rs.getLong("id"),
            rs.getTimestamp("unlocked_at").toInstant(),
            rs.getString("public_token"),
            rs.getString("type_code").trim() + "-" + rs.getString("energy").trim(),
            rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC).toLocalDate());

    private final NamedParameterJdbcTemplate jdbc;

    public TailsonalityTimelineQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分页源：严格早于 {@code upperBound}，按 unlocked_at 倒序（同刻按 id 倒序）取 {@code limit} 条。 */
    @Transactional(readOnly = true)
    public List<TailsonalityTimelineView> findForPetBefore(long petId, Instant upperBound, int limit) {
        return jdbc.query(SELECT + " AND unlocked_at < :upper ORDER BY unlocked_at DESC, id DESC LIMIT :limit",
                Map.of("petId", petId, "upper", Timestamp.from(upperBound), "limit", limit), ROW);
    }

    /** 日详情：unlocked_at 的 UTC 日 = {@code date}，按时间正序。 */
    @Transactional(readOnly = true)
    public List<TailsonalityTimelineView> findForPetOnUtcDate(long petId, LocalDate date) {
        return findForPetInUtcRange(petId, date, date.plusDays(1));
    }

    /** 日历：UTC 日 ∈ [fromInclusive, toExclusive)，按时间正序。 */
    @Transactional(readOnly = true)
    public List<TailsonalityTimelineView> findForPetInUtcRange(long petId, LocalDate fromInclusive,
            LocalDate toExclusive) {
        Instant from = fromInclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = toExclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbc.query(SELECT + " AND unlocked_at >= :from AND unlocked_at < :to ORDER BY unlocked_at ASC, id ASC",
                Map.of("petId", petId, "from", Timestamp.from(from), "to", Timestamp.from(to)), ROW);
    }
}
