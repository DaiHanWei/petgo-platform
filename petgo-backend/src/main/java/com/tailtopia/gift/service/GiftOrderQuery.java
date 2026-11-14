package com.tailtopia.gift.service;

import com.tailtopia.gift.web.dto.GiftOrderCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 逐笔礼物的只读查询（V1.5.0 gift-pack Story 1-4 · D-12）。只读 {@code gift_orders}（+ 分类过滤时 join {@code gift_catalog}），
 * 只列 {@code status = 'DELIVERED'}。本类不打日志。
 */
@Service
public class GiftOrderQuery {

    static final String COLUMNS = """
            o.id, o.public_token, o.pet_id, o.sender_id, o.gift_id, o.gift_name_en, o.gift_name_id, o.gift_icon_url,
            o.unit_price, o.quantity, o.delivered_at, o.opened_at, o.message, o.message_hidden_at
            """;
    /** 主人视角排序位：未拆 1 / 已拆 0。 */
    static final String UNOPENED_FLAG = "(CASE WHEN o.opened_at IS NULL THEN 1 ELSE 0 END)";

    private final NamedParameterJdbcTemplate jdbc;

    public GiftOrderQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一页（多取 1 行由调用方判 hasMore）。
     *
     * @param ownerView    主人视角：未拆在前；{@code unopenedOnly} 只列未拆。访客视角忽略 {@code unopenedOnly}
     * @param categoryId   礼物<b>当前</b>所在分类过滤（可空）
     */
    @Transactional(readOnly = true)
    public List<GiftOrderRow> page(long petId, boolean ownerView, boolean unopenedOnly, Long categoryId,
            GiftOrderCursor after, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("petId", petId).addValue("limit", limit);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM gift_orders o");
        if (categoryId != null) {
            sql.append(" JOIN gift_catalog g ON g.id = o.gift_id");
        }
        sql.append(" WHERE o.pet_id = :petId AND o.status = 'DELIVERED'");
        if (categoryId != null) {
            sql.append(" AND g.category_id = :cid");
            p.addValue("cid", categoryId);
        }
        if (ownerView && unopenedOnly) {
            sql.append(" AND o.opened_at IS NULL");
        }
        if (after != null) {
            p.addValue("ct", Timestamp.from(after.deliveredAt())).addValue("ci", after.id());
            String timeId = "(o.delivered_at < :ct OR (o.delivered_at = :ct AND o.id < :ci))";
            if (ownerView) {
                p.addValue("cu", after.unopened());
                sql.append(" AND (").append(UNOPENED_FLAG).append(" < :cu OR (").append(UNOPENED_FLAG)
                        .append(" = :cu AND ").append(timeId).append("))");
            } else {
                sql.append(" AND ").append(timeId);
            }
        }
        sql.append(ownerView ? " ORDER BY " + UNOPENED_FLAG + " DESC, o.delivered_at DESC, o.id DESC"
                : " ORDER BY o.delivered_at DESC, o.id DESC");
        sql.append(" LIMIT :limit");
        return jdbc.query(sql.toString(), p, ROW);
    }

    /** 这只宠物的未拆笔数。 */
    @Transactional(readOnly = true)
    public int unopenedCount(long petId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM gift_orders WHERE pet_id = :petId AND status = 'DELIVERED' AND opened_at IS NULL
                """, Map.of("petId", petId), Integer.class);
        return n == null ? 0 : n;
    }

    /** 按 token 取已送达订单（宠物已删 = pet_id 为空，也返回，由调用方判 404）。 */
    @Transactional(readOnly = true)
    public Optional<GiftOrderRow> deliveredByToken(String token) {
        return jdbc.query("SELECT " + COLUMNS + " FROM gift_orders o WHERE o.public_token = :t AND o.status = 'DELIVERED'",
                Map.of("t", token), ROW).stream().findFirst();
    }

    /** 按 id 批量取（拆礼物响应），按送达时刻倒序。 */
    @Transactional(readOnly = true)
    public List<GiftOrderRow> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM gift_orders o WHERE o.id IN (:ids)"
                + " ORDER BY o.delivered_at DESC, o.id DESC", Map.of("ids", ids), ROW);
    }

    /** 这只宠物名下、给定 token 中已送达的订单 id（不属于这只宠物的 token 忽略）。 */
    @Transactional(readOnly = true)
    public List<Long> deliveredIdsOfPet(long petId, Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT id FROM gift_orders WHERE pet_id = :petId AND status = 'DELIVERED' AND public_token IN (:t)
                """, Map.of("petId", petId, "t", tokens), Long.class);
    }

    static final RowMapper<GiftOrderRow> ROW = GiftOrderQuery::map;

    private static GiftOrderRow map(ResultSet rs, int i) throws SQLException {
        long petId = rs.getLong("pet_id");
        Long pet = rs.wasNull() ? null : petId;
        return new GiftOrderRow(rs.getLong("id"), rs.getString("public_token"), pet, rs.getLong("sender_id"),
                rs.getLong("gift_id"), rs.getString("gift_name_en"), rs.getString("gift_name_id"),
                rs.getString("gift_icon_url"), rs.getLong("unit_price"), rs.getInt("quantity"),
                instant(rs, "delivered_at"), instant(rs, "opened_at"), rs.getString("message"),
                instant(rs, "message_hidden_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
