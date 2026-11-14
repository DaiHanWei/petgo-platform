package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.moderation.dto.GiftMessageReviewView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一复核工作台「祝福语举报」只读查询（V1.5.0 gift-pack Story 1-5 · AC3.4）。写入只经
 * {@code gift.service.GiftMessageReportService}。本类不打印任何行内容（祝福语、昵称）。
 */
@Service
public class AdminGiftMessageReviewQuery {

    /** 一条举报单。 */
    public record Entry(long id, long reporterId, String reasonType, String status, Long handledBy,
            Instant handledAt, Instant createdAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public AdminGiftMessageReviewQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Entry> entries(long orderId) {
        return jdbc.query("""
                SELECT id, reporter_user_id, reason_type, status, handled_by, handled_at, created_at
                  FROM gift_message_reports WHERE gift_order_id = :oid ORDER BY created_at ASC, id ASC
                """, Map.of("oid", orderId), (rs, i) -> {
                    long hb = rs.getLong("handled_by");
                    Long handledBy = rs.wasNull() ? null : hb;
                    return new Entry(rs.getLong("id"), rs.getLong("reporter_user_id"), rs.getString("reason_type"),
                            rs.getString("status"), handledBy, instant(rs.getTimestamp("handled_at")),
                            instant(rs.getTimestamp("created_at")));
                });
    }

    /** 订单快照；订单不存在 → empty。 */
    @Transactional(readOnly = true)
    public Optional<GiftMessageReviewView> snapshot(long orderId) {
        List<GiftMessageReviewView> rows = jdbc.query("""
                SELECT o.id, o.message, o.message_hidden_at, o.gift_name_en, o.gift_name_id, o.quantity,
                       o.sender_id, u.nickname, (u.id IS NULL OR u.deleted_at IS NOT NULL) AS sender_deleted, o.pet_name
                  FROM gift_orders o
                  LEFT JOIN users u ON u.id = o.sender_id
                 WHERE o.id = :oid
                """, Map.of("oid", orderId), (rs, i) -> {
                    boolean deleted = rs.getBoolean("sender_deleted");
                    return new GiftMessageReviewView(rs.getLong("id"), rs.getString("message"),
                            rs.getTimestamp("message_hidden_at") != null, rs.getString("gift_name_en"),
                            rs.getString("gift_name_id"), rs.getInt("quantity"),
                            deleted ? null : rs.getLong("sender_id"), deleted ? null : rs.getString("nickname"),
                            deleted, rs.getString("pet_name"), Map.of());
                });
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Long> reasons = new TreeMap<>();
        for (Entry e : entries(orderId)) {
            reasons.merge(e.reasonType(), 1L, Long::sum);
        }
        GiftMessageReviewView v = rows.get(0);
        return Optional.of(new GiftMessageReviewView(v.orderId(), v.message(), v.hidden(), v.giftNameEn(),
                v.giftNameId(), v.quantity(), v.senderUserId(), v.senderNickname(), v.senderDeleted(), v.petName(),
                java.util.Collections.unmodifiableMap(reasons)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
