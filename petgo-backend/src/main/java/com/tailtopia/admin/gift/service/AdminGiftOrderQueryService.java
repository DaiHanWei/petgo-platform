package com.tailtopia.admin.gift.service;

import com.tailtopia.admin.gift.dto.GiftOrderAdminRow;
import com.tailtopia.admin.gift.dto.GiftOrderSummary;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.gift.domain.GiftOrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台送礼记录查询（V1.4.0 ③ gift Story 3.2 · AB-36，UI M1 ~ M3）。只读，{@code gift_orders} 原生 SQL。
 *
 * <ul>
 *   <li>🔴 <b>禁 N+1</b>：一页的送礼人 / 主人名与 QRIS 意图 token 用一条 {@code LEFT JOIN users} ×2 +
 *       {@code LEFT JOIN payment_intents} 取齐；宠物名取订单快照 {@code pet_name}，<b>不 JOIN 宠物表、不经
 *       {@code GiftPetDirectory}</b>（多宠物合入后零改动）。一页 = 列表 + 计数两条，摘要一条。</li>
 *   <li>🔴 WHERE 只由<b>固定 SQL 片段</b>按「有没有这个筛选」拼接，值一律走命名参数绑定，绝不拼值；
 *       不用 {@code (:x IS NULL OR …)} 写法 —— PostgreSQL 预编译对空参数推不出类型。</li>
 *   <li>时间段按 WIB 解释：{@code [from 00:00 WIB, to + 1 天 00:00 WIB)}（止日<b>含当天</b>，同支付记录）。</li>
 *   <li>注销用户（{@code users.deleted_at IS NOT NULL}）不取名字：显示「（已注销）#id」，不读注销前快照昵称（AC6.1）。
 *       用户行缺失（理论上不会：注销只匿名化不删行）同样按已注销处理。</li>
 * </ul>
 */
@Service
public class AdminGiftOrderQueryService {

    /** 状态筛选（AC2.1）：{@code UNDELIVERED} = 三个异常状态的合集。 */
    public enum StatusGroup {
        DELIVERED(EnumSet.of(GiftOrderStatus.DELIVERED)),
        PENDING(EnumSet.of(GiftOrderStatus.PENDING)),
        EXPIRED(EnumSet.of(GiftOrderStatus.EXPIRED)),
        FAILED(EnumSet.of(GiftOrderStatus.FAILED)),
        UNDELIVERED(EnumSet.of(GiftOrderStatus.UNDELIVERED_REFUND_PENDING, GiftOrderStatus.UNDELIVERED_REFUNDED,
                GiftOrderStatus.REFUND_MANUAL));

        private final Set<GiftOrderStatus> statuses;

        StatusGroup(Set<GiftOrderStatus> statuses) {
            this.statuses = statuses;
        }

        public Set<GiftOrderStatus> statuses() {
            return statuses;
        }

        /** 宽松解析：空 / 不认识 → {@code null}（= 不限），绝不抛。 */
        public static StatusGroup parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** 送礼只有两个渠道（{@code ck_gift_orders_channel}）。 */
    public static final List<String> CHANNELS = List.of("PAWCOIN", "QRIS");

    /**
     * 筛选条件。任一为 {@code null} = 不限。
     *
     * @param channel {@code PAWCOIN} / {@code QRIS}（经 {@link #parseChannel} 宽松解析）
     * @param from    起（含），按 WIB 当天 00:00 换算
     * @param to      止（<b>含当天</b>），按 WIB 次日 00:00 换算
     */
    public record Filter(Long senderId, Long ownerId, String channel, StatusGroup status, LocalDate from,
            LocalDate to) {

        /** 状态筛「已付款未送达」时换成异常单视图（AC3）。 */
        public boolean anomalyView() {
            return status == StatusGroup.UNDELIVERED;
        }
    }

    /** 渠道宽松解析：只认 {@code PAWCOIN} / {@code QRIS}（大小写不敏感），其余 → {@code null}（不限）。 */
    public static String parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return CHANNELS.contains(v) ? v : null;
    }

    /** 列表 / 抽屉共用的 SELECT（一行取齐名字与 token，宠物名取快照）。 */
    static final String SELECT_ROWS = """
            SELECT o.created_at, o.sender_id, o.owner_id, o.pet_id, o.pet_name,
                   o.gift_name_en, o.gift_name_id, o.unit_price, o.quantity, o.total, o.channel, o.status,
                   o.undelivered_reason, o.refund_attempts, o.refunded_at,
                   (s.id IS NULL OR s.deleted_at IS NOT NULL) AS s_deleted, COALESCE(s.nickname, s.display_name) AS s_name,
                   (w.id IS NULL OR w.deleted_at IS NOT NULL) AS w_deleted, COALESCE(w.nickname, w.display_name) AS w_name,
                   pi.public_token AS intent_token
              FROM gift_orders o
              LEFT JOIN users s ON s.id = o.sender_id
              LEFT JOIN users w ON w.id = o.owner_id
              LEFT JOIN payment_intents pi ON pi.id = o.payment_intent_id
            """;

    static final String ORDER_BY = " ORDER BY o.created_at DESC, o.id DESC";

    static final String SUMMARY = """
            SELECT count(*) AS orders,
                   count(DISTINCT o.sender_id) AS senders,
                   COALESCE(SUM(o.total) FILTER (WHERE o.status = 'DELIVERED'), 0) AS delivered_amount,
                   COALESCE(SUM(o.total) FILTER (WHERE o.status = 'DELIVERED' AND o.channel = 'PAWCOIN'), 0) AS delivered_coin,
                   count(*) FILTER (WHERE o.status IN ('UNDELIVERED_REFUND_PENDING', 'UNDELIVERED_REFUNDED', 'REFUND_MANUAL')) AS undelivered,
                   COALESCE(SUM(o.total) FILTER (WHERE o.status IN ('UNDELIVERED_REFUND_PENDING', 'UNDELIVERED_REFUNDED', 'REFUND_MANUAL')), 0) AS undelivered_amount,
                   count(*) FILTER (WHERE o.status = 'UNDELIVERED_REFUNDED') AS refunded,
                   count(*) FILTER (WHERE o.status IN ('UNDELIVERED_REFUND_PENDING', 'REFUND_MANUAL')) AS refund_failed
              FROM gift_orders o
            """;

    static final RowMapper<GiftOrderAdminRow> ROW = AdminGiftOrderQueryService::mapRow;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminGiftOrderQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按筛选条件分页查（{@code created_at DESC, id DESC}）：列表 + 计数两条 SQL。 */
    @Transactional(readOnly = true)
    public Page<GiftOrderAdminRow> search(Filter f, int page, int size) {
        int p = Math.max(page, 0);
        Where w = where(f);
        MapSqlParameterSource params = w.params().addValue("limit", size).addValue("offset", (long) p * size);
        List<GiftOrderAdminRow> rows = jdbc.query(SELECT_ROWS + w.sql() + ORDER_BY + " LIMIT :limit OFFSET :offset",
                params, ROW);
        Long total = jdbc.queryForObject("SELECT count(*) FROM gift_orders o" + w.sql(), w.params(), Long.class);
        return new PageImpl<>(rows, PageRequest.of(p, size), total == null ? 0L : total);
    }

    /**
     * 按筛选条件取前 {@code limit} 条（导出用，不分页）。调用方传「上限 + 1」判截断，不额外发 count。
     */
    @Transactional(readOnly = true)
    public List<GiftOrderAdminRow> searchAll(Filter f, int limit) {
        Where w = where(f);
        return jdbc.query(SELECT_ROWS + w.sql() + ORDER_BY + " LIMIT :limit",
                w.params().addValue("limit", limit), ROW);
    }

    /** 摘要条（AC2.4 / AC3.2）：覆盖整个筛选结果，一条 SQL 聚合（两种视图的数一并算出）。 */
    @Transactional(readOnly = true)
    public GiftOrderSummary summarize(Filter f) {
        Where w = where(f);
        GiftOrderSummary s = jdbc.queryForObject(SUMMARY + w.sql(), w.params(), (rs, i) -> new GiftOrderSummary(
                rs.getLong("orders"), rs.getLong("senders"), rs.getLong("delivered_amount"),
                rs.getLong("delivered_coin"), rs.getLong("undelivered"), rs.getLong("undelivered_amount"),
                rs.getLong("refunded"), rs.getLong("refund_failed")));
        return s == null ? GiftOrderSummary.EMPTY : s;
    }

    /** WHERE 片段 + 参数。值只走绑定；片段全是常量。 */
    record Where(String sql, MapSqlParameterSource params) {
    }

    static Where where(Filter f) {
        List<String> ps = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (f.senderId() != null) {
            ps.add("o.sender_id = :senderId");
            params.addValue("senderId", f.senderId());
        }
        if (f.ownerId() != null) {
            ps.add("o.owner_id = :ownerId");
            params.addValue("ownerId", f.ownerId());
        }
        if (f.channel() != null) {
            ps.add("o.channel = :channel");
            params.addValue("channel", f.channel());
        }
        if (f.status() != null) {
            ps.add("o.status IN (:statuses)");
            params.addValue("statuses", f.status().statuses().stream().map(Enum::name)
                    .sorted().collect(Collectors.toList()));
        }
        if (f.from() != null) {
            ps.add("o.created_at >= :fromTs");
            params.addValue("fromTs", Timestamp.from(f.from().atStartOfDay(AdminTime.ZONE).toInstant()));
        }
        if (f.to() != null) {
            // ⚠️ 含当天：上界取次日 00:00 且用 < —— 写成 <= 当天 00:00 会让「起止选同一天」永远查不到。
            ps.add("o.created_at < :toTs");
            params.addValue("toTs", Timestamp.from(f.to().plusDays(1).atStartOfDay(AdminTime.ZONE).toInstant()));
        }
        return new Where(ps.isEmpty() ? "" : " WHERE " + String.join(" AND ", ps), params);
    }

    static GiftOrderAdminRow mapRow(ResultSet rs, int i) throws SQLException {
        boolean sDeleted = rs.getBoolean("s_deleted");
        boolean wDeleted = rs.getBoolean("w_deleted");
        return new GiftOrderAdminRow(
                instant(rs, "created_at"),
                rs.getLong("sender_id"),
                sDeleted ? null : rs.getString("s_name"),
                sDeleted,
                rs.getLong("owner_id"),
                wDeleted ? null : rs.getString("w_name"),
                wDeleted,
                rs.getString("pet_name"),
                rs.getObject("pet_id") == null,
                rs.getString("gift_name_en"),
                rs.getString("gift_name_id"),
                rs.getLong("unit_price"),
                rs.getInt("quantity"),
                rs.getLong("total"),
                rs.getString("channel"),
                rs.getString("status"),
                rs.getString("undelivered_reason"),
                rs.getInt("refund_attempts"),
                instant(rs, "refunded_at"),
                rs.getString("intent_token"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
