package com.tailtopia.admin.shop.service;

import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「退款打款」列表与摘要（V1.4.2 shop-admin Story 5.2 · AD-10 · E1 / E2）。只读。
 *
 * <p>只取 {@code cash_refund > 0} 的电商取消退款单（不含退货单）。页签：待用户选择 / 待打款 / 打款失败 / 已打款
 * （COMPLETED ∧ TO_BANK）/ 已退为 PawCoin（COMPLETED ∧ TO_PAWCOIN）；用户已注销的单一律只进「注销用户·线下处理」页签（O-17），
 * 不计角标。页签按 {@code status} 判断（打款失败后用户重选，失败时刻与备注不会被清空）。
 *
 * <p>🔒 列表只给账号末 4 位与户名；完整账号只在抽屉给 {@code refund.payout}（Controller 判权）；本类不记日志。
 */
@Service
public class AdminShopRefundPayoutService {

    public static final int PAGE_SIZE = 50;
    static final Duration OVERDUE = Duration.ofDays(7);

    public enum Tab {
        AWAITING("r.status = 'AWAITING_DESTINATION'", "r.created_at ASC"),
        PENDING("r.status = 'PAYOUT_PENDING'", "r.destination_chosen_at ASC NULLS LAST"),
        FAILED("r.status = 'PAYOUT_FAILED'", "r.payout_failed_at DESC NULLS LAST"),
        PAID("r.status = 'COMPLETED' AND r.cash_destination = 'TO_BANK'", "r.paid_out_at DESC NULLS LAST"),
        PAWCOIN("r.status = 'COMPLETED' AND r.cash_destination = 'TO_PAWCOIN'", "r.updated_at DESC"),
        DELETED(null, "r.created_at DESC");

        final String where;
        final String order;

        Tab(String where, String order) {
            this.where = where;
            this.order = order;
        }

        public static Tab parse(String raw) {
            if (raw != null) {
                for (Tab t : values()) {
                    if (t.name().equalsIgnoreCase(raw.trim())) {
                        return t;
                    }
                }
            }
            return AWAITING;
        }
    }

    /** 列表行。🔒 {@code accountLast4} 只有末 4 位。 */
    public record Row(String refundToken, String orderToken, String displayNo, String userName, long cashRefund,
            String reason, String scope, String channel, String accountLast4, long payoutFee, String holder,
            Instant chosenAt, String status, boolean overdue, boolean offlineClosed) {
    }

    /** 摘要：待用户选择金额、其中超 7 天未选笔数、待打款金额、打款失败笔数。 */
    public record Summary(long awaitingAmount, long overdueCount, long pendingAmount, long failedCount) {
    }

    private final JdbcTemplate jdbc;
    private final ShopOrderRefundRepository refunds;
    private final Clock clock;

    @Autowired
    public AdminShopRefundPayoutService(JdbcTemplate jdbc, ShopOrderRefundRepository refunds) {
        this(jdbc, refunds, Clock.systemUTC());
    }

    AdminShopRefundPayoutService(JdbcTemplate jdbc, ShopOrderRefundRepository refunds, Clock clock) {
        this.jdbc = jdbc;
        this.refunds = refunds;
        this.clock = clock;
    }

    private static final String DELETED = "EXISTS (SELECT 1 FROM account_deletions d WHERE d.user_id = o.user_id)";

    static String whereOf(Tab tab) {
        return "r.cash_refund > 0 AND " + (tab == Tab.DELETED ? DELETED : tab.where + " AND NOT " + DELETED);
    }

    @Transactional(readOnly = true)
    public List<Row> list(Tab tab, int page) {
        Instant overdueBefore = clock.instant().minus(OVERDUE);
        List<Object[]> base = jdbc.query("""
                SELECT r.id, r.public_token, o.public_token, o.display_no, u.nickname, r.cash_refund, r.reason, r.scope,
                       r.destination_chosen_at, r.status, r.created_at, r.offline_closed_at
                  FROM shop_order_refunds r JOIN shop_orders o ON o.id = r.order_id
                  LEFT JOIN users u ON u.id = o.user_id
                 WHERE""" + " " + whereOf(tab) + " ORDER BY " + tab.order + ", r.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getString(10),
                        rs.getTimestamp(11).toInstant(), rs.getTimestamp(12) != null},
                PAGE_SIZE, Math.max(0, page) * PAGE_SIZE);
        // 账号 / 户名是加密列：按 id 批量取实体，由实体转换器解密后只截末 4 位
        Map<Long, ShopOrderRefund> byId = new HashMap<>();
        refunds.findAllById(base.stream().map(b -> (Long) b[0]).toList()).forEach(r -> byId.put(r.getId(), r));
        List<Row> out = new ArrayList<>();
        for (Object[] b : base) {
            ShopOrderRefund r = byId.get((Long) b[0]);
            String status = (String) b[9];
            out.add(new Row((String) b[1], (String) b[2], (String) b[3], (String) b[4], (Long) b[5], (String) b[6],
                    (String) b[7], r == null || r.getPayoutChannel() == null ? null : r.getPayoutChannel().name(),
                    r == null ? null : r.payoutAccountLast4(), r == null ? 0L : r.getPayoutFee(),
                    r == null ? null : r.getPayoutAccountHolder(), (Instant) b[8], status,
                    "AWAITING_DESTINATION".equals(status) && ((Instant) b[10]).isBefore(overdueBefore),
                    (Boolean) b[11]));
        }
        return out;
    }

    /** 各页签条数（页签计数）。 */
    @Transactional(readOnly = true)
    public Map<Tab, Long> counts() {
        Map<Tab, Long> m = new LinkedHashMap<>();
        for (Tab t : Tab.values()) {
            m.put(t, jdbc.queryForObject("SELECT count(*) FROM shop_order_refunds r JOIN shop_orders o ON o.id = r.order_id"
                    + " WHERE " + whereOf(t), Long.class));
        }
        return m;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Timestamp overdueBefore = Timestamp.from(clock.instant().minus(OVERDUE));
        String from = " FROM shop_order_refunds r JOIN shop_orders o ON o.id = r.order_id WHERE ";
        long awaiting = jdbc.queryForObject("SELECT coalesce(sum(r.cash_refund), 0)" + from + whereOf(Tab.AWAITING),
                Long.class);
        long overdue = jdbc.queryForObject("SELECT count(*)" + from + whereOf(Tab.AWAITING) + " AND r.created_at < ?",
                Long.class, overdueBefore);
        long pending = jdbc.queryForObject("SELECT coalesce(sum(r.cash_refund), 0)" + from + whereOf(Tab.PENDING),
                Long.class);
        long failed = jdbc.queryForObject("SELECT count(*)" + from + whereOf(Tab.FAILED), Long.class);
        return new Summary(awaiting, overdue, pending, failed);
    }

    /**
     * V1.4.2 shop-admin Story 5.3：待办中心「退款打款」角标 = 待打款 + 打款失败 + 待用户选择中超 7 天未选（AD-2）；
     * 注销用户的单不计（O-17 / O-26）。
     */
    @Transactional(readOnly = true)
    public long badgeCount() {
        String from = "SELECT count(*) FROM shop_order_refunds r JOIN shop_orders o ON o.id = r.order_id WHERE ";
        long pending = jdbc.queryForObject(from + whereOf(Tab.PENDING), Long.class);
        long failed = jdbc.queryForObject(from + whereOf(Tab.FAILED), Long.class);
        long overdue = jdbc.queryForObject(from + whereOf(Tab.AWAITING) + " AND r.created_at < ?", Long.class,
                Timestamp.from(clock.instant().minus(OVERDUE)));
        return pending + failed + overdue;
    }

    /** 抽屉：发起人后台显示名（账号已删 → null）。 */
    @Transactional(readOnly = true)
    public String adminName(Long adminId) {
        if (adminId == null) {
            return null;
        }
        return jdbc.query("SELECT display_name FROM admin_accounts WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null,
                adminId);
    }

    /** 抽屉：订单展示号、订单 token、用户昵称、用户是否已注销。 */
    public record OrderInfo(String orderToken, String displayNo, String userName, boolean userDeleted) {
    }

    @Transactional(readOnly = true)
    public OrderInfo orderInfo(long orderId) {
        return jdbc.query("SELECT o.public_token, o.display_no, u.nickname, " + DELETED
                + " FROM shop_orders o LEFT JOIN users u ON u.id = o.user_id WHERE o.id = ?",
                rs -> rs.next() ? new OrderInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))
                        : null, orderId);
    }
}
