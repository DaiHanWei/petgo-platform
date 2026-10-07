package com.tailtopia.place.service;

import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.dto.PlaceCheckinTimelineView;
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
 * Diary 时间线的打卡只读口（V1.3.2 Story 1.6 · AD-9）。profile 经它取数，<b>不直接 join</b> place 表。
 *
 * <p>按 {@code place_checkin_pets.pet_profile_id} 落到<b>打卡所选宠物</b>；场所取打卡<b>当前</b>
 * {@code place_id}（合并后是保留方）。UTC 日界用半开区间比较 {@code checked_at}，
 * <b>不在 SQL 里做时区敏感的 date 转换</b>（{@code ContentService} 日期查询同一理由）。
 */
@Service
public class PlaceCheckinTimelineQuery {

    private static final String SELECT = """
            SELECT c.id AS checkin_id, c.checked_at, p.public_token, p.name, p.status, p.deleted_at
              FROM place_checkin_pets cp
              JOIN place_checkins c ON c.id = cp.checkin_id
              JOIN places p ON p.id = c.place_id
             WHERE cp.pet_profile_id = :petId
            """;

    private static final RowMapper<PlaceCheckinTimelineView> ROW = (rs, i) -> {
        Timestamp deleted = rs.getTimestamp("deleted_at");
        return new PlaceCheckinTimelineView(
                rs.getLong("checkin_id"),
                rs.getTimestamp("checked_at").toInstant(),
                rs.getString("public_token"),
                rs.getString("name"),
                PlaceAvailability.of(rs.getString("status"), deleted == null ? null : deleted.toInstant()).name());
    };

    private final NamedParameterJdbcTemplate jdbc;

    public PlaceCheckinTimelineQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分页源：严格早于 {@code upperBound}，按 checked_at 倒序（同刻按 id 倒序）取 {@code limit} 条。 */
    @Transactional(readOnly = true)
    public List<PlaceCheckinTimelineView> findForPetBefore(long petId, Instant upperBound, int limit) {
        return jdbc.query(SELECT + " AND c.checked_at < :upper ORDER BY c.checked_at DESC, c.id DESC LIMIT :limit",
                Map.of("petId", petId, "upper", Timestamp.from(upperBound), "limit", limit), ROW);
    }

    /** 日详情：checked_at 的 UTC 日 = {@code date}，按时间正序。 */
    @Transactional(readOnly = true)
    public List<PlaceCheckinTimelineView> findForPetOnUtcDate(long petId, LocalDate date) {
        return findForPetInUtcRange(petId, date, date.plusDays(1));
    }

    /** 日历：UTC 日 ∈ [fromInclusive, toExclusive)，按时间正序。 */
    @Transactional(readOnly = true)
    public List<PlaceCheckinTimelineView> findForPetInUtcRange(long petId, LocalDate fromInclusive,
            LocalDate toExclusive) {
        Instant from = fromInclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = toExclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbc.query(SELECT + " AND c.checked_at >= :from AND c.checked_at < :to ORDER BY c.checked_at ASC, c.id ASC",
                Map.of("petId", petId, "from", Timestamp.from(from), "to", Timestamp.from(to)), ROW);
    }
}
