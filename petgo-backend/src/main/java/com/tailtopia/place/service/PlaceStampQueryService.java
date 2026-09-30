package com.tailtopia.place.service;

import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceType;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 章聚合只读查询（V1.3.2 Story 1.2 · AD-5）。
 *
 * <p>🔴 <b>聚合放 place 包</b>：{@code place_checkins} / {@code place_checkin_pets} / {@code places}
 * 三表都归 place；passport 只消费结果。bean 依赖单向：
 * {@code PlaceCheckinService → PetPassportService}、{@code PetPassportController → PlaceStampQueryService}，
 * 本类不得反向注入打卡服务。Story 1.3（地址）/ 1.4（专属章 URL）加字段只动这里一条查询。
 */
@Service
public class PlaceStampQueryService {

    /** 按当前 place_id 分组；排序 = 首次日期升序、首条打卡 id 升序（新章恒在最后）。 */
    static final String STAMPS_SQL = """
            SELECT p.public_token AS place_token, p.name AS place_name, p.place_type,
                   p.status, p.deleted_at, p.address_text,
                   MIN(c.visit_date) AS first_visit, COUNT(*) AS visits, MIN(c.id) AS first_id
              FROM place_checkin_pets cp
              JOIN place_checkins c ON c.id = cp.checkin_id
              JOIN places p ON p.id = c.place_id
             WHERE cp.pet_profile_id = :petId
             GROUP BY p.id, p.public_token, p.name, p.place_type, p.status, p.deleted_at, p.address_text
             ORDER BY first_visit ASC, first_id ASC""";

    static final String STAMP_COUNT_SQL = """
            SELECT COUNT(DISTINCT c.place_id)
              FROM place_checkin_pets cp
              JOIN place_checkins c ON c.id = cp.checkin_id
             WHERE cp.pet_profile_id = :petId""";

    private final NamedParameterJdbcTemplate jdbc;

    public PlaceStampQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 该宠物的全部章（可能为空表）。 */
    @Transactional(readOnly = true)
    public List<PlaceStamp> stampsOf(long petId) {
        return jdbc.query(STAMPS_SQL, Map.of("petId", petId), (rs, i) -> {
            Timestamp deletedAt = rs.getTimestamp("deleted_at");
            PlaceAvailability availability = PlaceAvailability.of(rs.getString("status"),
                    deletedAt == null ? null : deletedAt.toInstant());
            return new PlaceStamp(
                    rs.getString("place_token"),
                    rs.getString("place_name"),
                    typeOf(rs.getString("place_type")),
                    availability,
                    rs.getDate("first_visit").toLocalDate(),
                    rs.getLong("visits"),
                    // Story 1.3：地址只对 ACTIVE 下发（B6：不向客户端泄漏已下架场所的位置）。
                    availability == PlaceAvailability.ACTIVE ? rs.getString("address_text") : null);
        });
    }

    /** 章数 = 该宠物打过卡的不同当前 place_id 数（无分母，AC3.3）。 */
    @Transactional(readOnly = true)
    public int stampCountOf(long petId) {
        Long n = jdbc.queryForObject(STAMP_COUNT_SQL, Map.of("petId", petId), Long.class);
        return n == null ? 0 : n.intValue();
    }

    private static PlaceType typeOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PlaceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null; // 库里出现客户端未知的类型：不让整本护照 500，章面回落通用占位
        }
    }
}
