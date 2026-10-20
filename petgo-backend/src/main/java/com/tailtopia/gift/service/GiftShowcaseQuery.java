package com.tailtopia.gift.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 礼物展示的只读查询（V1.4.0 gift Story 2.1 · AD-3 / AD-10）：礼物页汇总、礼物墙、粉丝榜、统计卡总价值、详情礼物区。
 *
 * <p>🔴 总价值、墙、粉丝榜<b>一律读汇总表</b>（{@code gift_pet_wall} / {@code gift_pet_supporters}，只在交付同事务由
 * {@code GiftDeliveryService} 写入、只含 DELIVERED）。{@code gift_orders} 只在粉丝行「礼物 × 数量」聚合里读，且限定
 * {@code status = 'DELIVERED'} 与本页送礼人 —— <b>不用于总价值</b>（退回 / 待付 / 过期等一律不计）。
 *
 * <p>🔴 本类只依赖 JDBC，<b>不反向依赖 profile / content 的 service</b>（{@code TimelineService} /
 * {@code ContentDetailService} 注入本类，反过来就成环）。宠物信息只经 {@code GiftPetDirectory} 取，本类不碰宠物表。
 *
 * <p>查询次数为常数：礼物页 = 汇总 1 + 墙 1；粉丝页 = 榜 1 + 礼物聚合 1（+ 作者投影 1，在调用方）；详情 = 汇总 1 + 墙前 N 1。
 */
@Service
public class GiftShowcaseQuery {

    /** 统计卡总价值（AD-3：Σ unit_price × count）。按<b>宠物</b>算，不按主人汇总。 */
    static final String TOTAL_VALUE_SQL = """
            SELECT COALESCE(SUM(unit_price * count), 0)
              FROM gift_pet_wall
             WHERE pet_id = :petId
            """;

    /** 批量汇总（为多宠留形状）：总价值、礼物个数、墙行数（种类 × 价位）。 */
    static final String SUMMARIES_SQL = """
            SELECT pet_id,
                   COALESCE(SUM(unit_price * count), 0) AS total_value,
                   COALESCE(SUM(count), 0)              AS gift_count,
                   COUNT(*)                             AS kind_count
              FROM gift_pet_wall
             WHERE pet_id IN (:petIds)
             GROUP BY pet_id
            """;

    /** 礼物墙全部行：单价降序、同价数量降序、再按 gift_id 兜底稳定。只取快照列，不 join 目录取现值（AC17）。 */
    static final String WALL_SQL = """
            SELECT gift_id, gift_name_en, gift_name_id, gift_icon_url, unit_price, count
              FROM gift_pet_wall
             WHERE pet_id = :petId
             ORDER BY unit_price DESC, count DESC, gift_id ASC
            """;

    /** 每只宠物的墙前 N 行（详情礼物区缩略），一次查询覆盖全部宠物。 */
    static final String WALL_PREVIEW_SQL = """
            SELECT pet_id, gift_id, gift_name_en, gift_name_id, gift_icon_url, unit_price, count
              FROM (SELECT w.*,
                           ROW_NUMBER() OVER (PARTITION BY w.pet_id
                                              ORDER BY w.unit_price DESC, w.count DESC, w.gift_id) AS rn
                      FROM gift_pet_wall w
                     WHERE w.pet_id IN (:petIds)) ranked
             WHERE rn <= :limit
             ORDER BY pet_id, rn
            """;

    /**
     * 主人视角排除其隐藏的送礼人（AC12a）：<b>不分来源</b>（BLOCK 与 REPORT 都排除），与
     * {@code UserHideRelationReader.isHidden} / {@code hiddenByHolder} 同口径。{@code :ownerId} 为 NULL（访客视角）时不排除。
     */
    private static final String HIDDEN_FILTER = """
               AND (CAST(:ownerId AS BIGINT) IS NULL OR NOT EXISTS (
                    SELECT 1 FROM user_hide_relations h
                     WHERE h.holder_id = CAST(:ownerId AS BIGINT) AND h.target_id = s.sender_id))
            """;

    /** 送礼人数（与粉丝榜同口径：主人视角排除其隐藏的人）。 */
    static final String SUPPORTER_COUNT_SQL = """
            SELECT COUNT(*)
              FROM gift_pet_supporters s
             WHERE s.pet_id = :petId
            """ + HIDDEN_FILTER;

    /** 粉丝榜一页：累计金额降序、同额最近送礼降序、再 sender_id 降序；keyset 续页（行值比较，三列同为 DESC）。 */
    static final String SUPPORTERS_SQL = """
            SELECT s.sender_id, s.total_amount, s.gift_count, s.last_sent_at
              FROM gift_pet_supporters s
             WHERE s.pet_id = :petId
            """ + HIDDEN_FILTER + """
               AND (CAST(:afterAmount AS BIGINT) IS NULL
                    OR (s.total_amount, s.last_sent_at, s.sender_id)
                       < (CAST(:afterAmount AS BIGINT), CAST(:afterSentAt AS TIMESTAMPTZ), CAST(:afterSender AS BIGINT)))
             ORDER BY s.total_amount DESC, s.last_sent_at DESC, s.sender_id DESC
             LIMIT :limit
            """;

    /**
     * 本页送礼人各自送给这只宠物的礼物（按种类 × 价位聚合）：只含 DELIVERED。名称 / 图标取该组<b>最早</b>一笔订单的快照，
     * 与墙「保留首次快照」同口径。
     */
    static final String SENDER_GIFTS_SQL = """
            SELECT o.sender_id, o.gift_id, o.unit_price,
                   (ARRAY_AGG(o.gift_name_en  ORDER BY o.id))[1] AS gift_name_en,
                   (ARRAY_AGG(o.gift_name_id  ORDER BY o.id))[1] AS gift_name_id,
                   (ARRAY_AGG(o.gift_icon_url ORDER BY o.id))[1] AS gift_icon_url,
                   SUM(o.quantity) AS count
              FROM gift_orders o
             WHERE o.pet_id = :petId
               AND o.status = 'DELIVERED'
               AND o.sender_id IN (:senderIds)
             GROUP BY o.sender_id, o.gift_id, o.unit_price
            """;

    /** 墙 / 粉丝行礼物的展示序：单价降序、数量降序、gift_id 升序。 */
    private static final Comparator<SenderGiftKind> KIND_ORDER = Comparator
            .comparingLong(SenderGiftKind::unitPrice).reversed()
            .thenComparing(Comparator.comparingLong(SenderGiftKind::count).reversed())
            .thenComparingLong(SenderGiftKind::giftId);

    private final NamedParameterJdbcTemplate jdbc;

    public GiftShowcaseQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一只宠物收到的礼物总价值（IDR，只含已送达）；无礼物为 0。 */
    public long totalValueOfPet(long petId) {
        Long v = jdbc.queryForObject(TOTAL_VALUE_SQL, Map.of("petId", petId), Long.class);
        return v == null ? 0L : v;
    }

    /** 批量汇总；没有礼物的宠物不在 Map 里（调用方按 {@link PetGiftSummary#EMPTY} 补）。 */
    public Map<Long, PetGiftSummary> summariesOf(Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PetGiftSummary> out = new HashMap<>();
        jdbc.query(SUMMARIES_SQL, Map.of("petIds", petIds), rs -> {
            out.put(rs.getLong("pet_id"), new PetGiftSummary(rs.getLong("total_value"),
                    rs.getLong("gift_count"), rs.getInt("kind_count")));
        });
        return out;
    }

    /** 一只宠物的礼物墙（全部种类 × 价位，不分页）。 */
    public List<WallRow> wall(long petId) {
        return jdbc.query(WALL_SQL, Map.of("petId", petId), (rs, i) -> new WallRow(rs.getLong("gift_id"),
                rs.getString("gift_name_en"), rs.getString("gift_name_id"), rs.getString("gift_icon_url"),
                rs.getLong("unit_price"), rs.getLong("count")));
    }

    /** 每只宠物墙前 {@code limit} 行（与 {@link #wall} 同序）；没有礼物的宠物不在 Map 里。 */
    public Map<Long, List<WallRow>> wallPreviewOf(Collection<Long> petIds, int limit) {
        if (petIds == null || petIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<WallRow>> out = new LinkedHashMap<>();
        jdbc.query(WALL_PREVIEW_SQL, Map.of("petIds", petIds, "limit", limit), rs -> {
            out.computeIfAbsent(rs.getLong("pet_id"), k -> new ArrayList<>()).add(new WallRow(rs.getLong("gift_id"),
                    rs.getString("gift_name_en"), rs.getString("gift_name_id"), rs.getString("gift_icon_url"),
                    rs.getLong("unit_price"), rs.getLong("count")));
        });
        return out;
    }

    /**
     * 送礼人数。
     *
     * @param hiddenForOwnerId 主人视角传主人 id（排除其隐藏的送礼人）；访客视角传 {@code null}
     */
    public long supporterCount(long petId, Long hiddenForOwnerId) {
        MapSqlParameterSource p = new MapSqlParameterSource("petId", petId)
                .addValue("ownerId", hiddenForOwnerId);
        Long v = jdbc.queryForObject(SUPPORTER_COUNT_SQL, p, Long.class);
        return v == null ? 0L : v;
    }

    /**
     * 粉丝榜一页（按排序键 keyset 续页）。
     *
     * @param hiddenForOwnerId 主人视角传主人 id；访客 {@code null}
     * @param after            上一页最后一行的排序键；首页 {@code null}
     * @param limit            取几行（调用方多取 1 行判断是否还有下一页）
     */
    public List<SupporterRow> supporters(long petId, Long hiddenForOwnerId, SupporterKey after, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("petId", petId)
                .addValue("ownerId", hiddenForOwnerId)
                .addValue("afterAmount", after == null ? null : after.totalAmount())
                .addValue("afterSentAt", after == null ? null : after.lastSentAt().atOffset(ZoneOffset.UTC))
                .addValue("afterSender", after == null ? null : after.senderId())
                .addValue("limit", limit);
        return jdbc.query(SUPPORTERS_SQL, p, (rs, i) -> new SupporterRow(rs.getLong("sender_id"),
                rs.getLong("total_amount"), rs.getLong("gift_count"),
                rs.getTimestamp("last_sent_at").toInstant()));
    }

    /** 本页送礼人各自的礼物（已按单价降序、数量降序排好）。一页一次查询，不按行查。 */
    public Map<Long, List<SenderGiftKind>> giftKindsBySender(long petId, Collection<Long> senderIds) {
        if (senderIds == null || senderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SenderGiftKind>> out = new HashMap<>();
        jdbc.query(SENDER_GIFTS_SQL, Map.of("petId", petId, "senderIds", senderIds), rs -> {
            out.computeIfAbsent(rs.getLong("sender_id"), k -> new ArrayList<>()).add(new SenderGiftKind(
                    rs.getLong("gift_id"), rs.getString("gift_icon_url"), rs.getString("gift_name_en"),
                    rs.getString("gift_name_id"), rs.getLong("unit_price"), rs.getLong("count")));
        });
        out.values().forEach(l -> l.sort(KIND_ORDER));
        return out;
    }

    /** 宠物礼物汇总。 */
    public record PetGiftSummary(long totalValue, long giftCount, int kindCount) {
        public static final PetGiftSummary EMPTY = new PetGiftSummary(0L, 0L, 0);
    }

    /** 墙一行（订单快照）。 */
    public record WallRow(long giftId, String nameEn, String nameId, String iconUrl, long unitPrice, long count) {
    }

    /** 粉丝榜一行（汇总表原值；{@code senderId} 只在服务端用，不下发）。 */
    public record SupporterRow(long senderId, long totalAmount, long giftCount, Instant lastSentAt) {
    }

    /** 粉丝榜排序键（keyset 续页）。 */
    public record SupporterKey(long totalAmount, Instant lastSentAt, long senderId) {
    }

    /** 某送礼人送给这只宠物的某种礼物（种类 × 价位）。 */
    public record SenderGiftKind(long giftId, String iconUrl, String nameEn, String nameId, long unitPrice,
            long count) {
    }
}
