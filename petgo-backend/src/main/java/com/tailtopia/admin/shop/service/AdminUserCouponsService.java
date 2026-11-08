package com.tailtopia.admin.shop.service;

import com.tailtopia.shop.coupon.service.CouponRedeemService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户详情「优惠券」页签（V1.4.2 shop-admin Story 2.3 · AD-6 · UI A11~A13）：按用户查券，<b>只读</b>。
 *
 * <p>行以 {@code user_coupons.public_token} 定位（不外露自增 id）；当前占用 / 使用的订单取订单展示号 + token。
 * 已退回的券订单字段已清空，历史靠 {@code user_coupon_events}（2.1）带出的订单。
 * 🔴 不提供修改券或补发入口；兑换锁定只显示剩余分钟（读 {@link CouponRedeemService}，不另写 Redis key）。
 */
@Service
public class AdminUserCouponsService {

    /** 单次最多列这么多张（客服排查用，足够；超出时页签提示只显示最近的）。 */
    static final int LIMIT = 200;

    private final JdbcTemplate jdbc;
    private final CouponRedeemService redeem;
    private final Clock clock;

    @Autowired
    public AdminUserCouponsService(JdbcTemplate jdbc, CouponRedeemService redeem) {
        this(jdbc, redeem, Clock.systemUTC());
    }

    AdminUserCouponsService(JdbcTemplate jdbc, CouponRedeemService redeem, Clock clock) {
        this.jdbc = jdbc;
        this.redeem = redeem;
        this.clock = clock;
    }

    /** 一条状态变化：事件、时刻、关联订单（展示号 + token，可空）。 */
    public record Event(String event, Instant at, String orderNo, String orderToken) {
    }

    /**
     * 一张用户券。{@code displayStatus}：AVAILABLE / EXPIRED（可用但已过有效期，现算）/ LOCKED / USED / VOIDED。
     */
    public record Row(String token, String source, String status, String displayStatus, String kind, long amount,
            long minSpend, String couponName, String couponNameEn, Instant claimedAt, Instant validUntil, String orderNo, String orderToken,
            List<Event> events) {
    }

    /** 页签数据：行 + 兑换锁定剩余分钟（null = 未锁定）+ 是否截断。 */
    public record Tab(long userId, List<Row> rows, Long lockMinutes, boolean truncated) {
    }

    /** 🔴 不开事务：两条只读查询各自短连接，Redis TTL 读取慢时不占着数据库连接。 */
    public Tab tab(long userId) {
        Instant now = clock.instant();
        List<Row> base = jdbc.query("""
                SELECT uc.id, uc.public_token, uc.source, uc.status, uc.claimed_at, uc.valid_until,
                       c.kind, c.amount, c.min_spend, c.name_id, c.name_en,
                       COALESCE(uo.display_no, lo.display_no) AS order_no,
                       COALESCE(uo.public_token, lo.public_token) AS order_token
                  FROM user_coupons uc
                  JOIN shop_coupons c ON c.id = uc.coupon_id
                  LEFT JOIN shop_orders lo ON lo.id = uc.locked_order_id
                  LEFT JOIN shop_orders uo ON uo.id = uc.used_order_id
                 WHERE uc.user_id = ?
                 ORDER BY uc.claimed_at DESC, uc.id DESC
                 LIMIT ?""", (rs, i) -> row(rs, now), userId, LIMIT + 1);
        boolean truncated = base.size() > LIMIT;
        List<Row> rows = truncated ? base.subList(0, LIMIT) : base;
        Map<String, List<Event>> events = eventsOf(userId);  // 只取页签里这 200 张的流水
        List<Row> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            out.add(new Row(r.token(), r.source(), r.status(), r.displayStatus(), r.kind(), r.amount(), r.minSpend(),
                    r.couponName(), r.couponNameEn(), r.claimedAt(), r.validUntil(), r.orderNo(), r.orderToken(),
                    events.getOrDefault(r.token(), List.of())));
        }
        Long lockMinutes = redeem.redeemLockRemainingSeconds(userId).map(s -> (s + 59) / 60).orElse(null);
        return new Tab(userId, List.copyOf(out), lockMinutes, truncated);
    }

    private Map<String, List<Event>> eventsOf(long userId) {
        Map<String, List<Event>> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT uc.public_token, e.event, e.created_at, o.display_no, o.public_token AS order_token
                  FROM user_coupon_events e
                  JOIN user_coupons uc ON uc.id = e.user_coupon_id
                  LEFT JOIN shop_orders o ON o.id = e.order_id
                 WHERE e.user_coupon_id IN (SELECT id FROM user_coupons WHERE user_id = ?
                                             ORDER BY claimed_at DESC, id DESC LIMIT ?)
                 ORDER BY e.id""", rs -> {
            out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Event(rs.getString(2),
                    ts(rs, 3), rs.getString(4), rs.getString(5)));
        }, userId, LIMIT);
        return out;
    }

    private static Row row(ResultSet rs, Instant now) throws SQLException {
        String status = rs.getString("status");
        Instant validUntil = ts(rs, "valid_until");
        String display = "AVAILABLE".equals(status) && validUntil != null && !now.isBefore(validUntil) ? "EXPIRED" : status;
        boolean showOrder = "LOCKED".equals(status) || "USED".equals(status);
        return new Row(rs.getString("public_token"), rs.getString("source"), status, display, rs.getString("kind"),
                rs.getLong("amount"), rs.getLong("min_spend"), rs.getString("name_id"), rs.getString("name_en"), ts(rs, "claimed_at"), validUntil,
                showOrder ? rs.getString("order_no") : null, showOrder ? rs.getString("order_token") : null, List.of());
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Instant ts(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
