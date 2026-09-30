package com.tailtopia.place.service;

import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceStampRef;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.shared.media.AliyunOssClient;
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
            SELECT p.id AS place_id, p.public_token AS place_token, p.name AS place_name, p.place_type,
                   p.status, p.deleted_at, p.address_text, p.stamp_object_key,
                   MIN(c.visit_date) AS first_visit, MAX(c.visit_date) AS last_visit, COUNT(*) AS visits,
                   MIN(c.id) AS first_id
              FROM place_checkin_pets cp
              JOIN place_checkins c ON c.id = cp.checkin_id
              JOIN places p ON p.id = c.place_id
             WHERE cp.pet_profile_id = :petId
             GROUP BY p.id, p.public_token, p.name, p.place_type, p.status, p.deleted_at, p.address_text,
                      p.stamp_object_key
             ORDER BY first_visit ASC, first_id ASC""";

    static final String STAMP_COUNT_SQL = """
            SELECT COUNT(DISTINCT c.place_id)
              FROM place_checkin_pets cp
              JOIN place_checkins c ON c.id = cp.checkin_id
             WHERE cp.pet_profile_id = :petId""";

    private final NamedParameterJdbcTemplate jdbc;
    /** Story 1.4：专属章 key → 公开 CDN URL。 */
    private final AliyunOssClient oss;

    public PlaceStampQueryService(NamedParameterJdbcTemplate jdbc, AliyunOssClient oss) {
        this.jdbc = jdbc;
        this.oss = oss;
    }

    /**
     * 专属章 objectKey → 公开 URL（Story 1.4 · AC4.1）。
     *
     * <p>🔴 <b>纯 CDN 地址，不拼 {@code x-oss-process}</b>：场所照片那条 {@code resize/format,jpg} 会把 PNG 重编码成 JPG、
     * 丢掉透明通道。专属章是运营素材、不含用户 EXIF，直接给原图。
     */
    public String stampUrlOf(String objectKey) {
        return objectKey == null || objectKey.isBlank() ? null : oss.publicUrl(objectKey);
    }

    /** 该宠物的全部章（可能为空表）。 */
    @Transactional(readOnly = true)
    public List<PlaceStamp> stampsOf(long petId) {
        return stampRefsOf(petId).stream().map(PlaceStampRef::stamp).toList();
    }

    /**
     * 同一条聚合 SQL 的只读投影，带内部 {@code placeId}（V1.3.2 Story 3.4：护照快照冻结 + 现算章集合 hash）。
     * 🔴 placeId 不得进任何对外 DTO。
     */
    @Transactional(readOnly = true)
    public List<PlaceStampRef> stampRefsOf(long petId) {
        return jdbc.query(STAMPS_SQL, Map.of("petId", petId), (rs, i) -> new PlaceStampRef(rs.getLong("place_id"),
                stampOf(rs), rs.getDate("last_visit").toLocalDate()));
    }

    private PlaceStamp stampOf(java.sql.ResultSet rs) throws java.sql.SQLException {
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
                availability == PlaceAvailability.ACTIVE ? rs.getString("address_text") : null,
                stampUrlOf(rs.getString("stamp_object_key")));
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
