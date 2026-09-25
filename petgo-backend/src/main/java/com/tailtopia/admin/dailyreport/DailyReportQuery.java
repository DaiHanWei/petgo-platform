package com.tailtopia.admin.dailyreport;

import static com.tailtopia.admin.dashboard.metrics.MetricSql.VALID_COMMENT_WHERE;
import static com.tailtopia.admin.dashboard.metrics.MetricSql.VISIBLE_POST_WHERE;
import static com.tailtopia.admin.dashboard.metrics.MetricSql.day;

import com.tailtopia.admin.dashboard.metrics.SyntheticAccountSql;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日报取数。口径与后台数据看板对齐（复用 {@code MetricSql} 的切日 / 可见帖 / 有效评论常量）：
 * <ul>
 *   <li>切日 = WIB 自然日；</li>
 *   <li>一律排除虚拟账号与管理员（{@link SyntheticAccountSql#EXCLUDE_WHERE}）—— 否则飞书定时发帖的
 *       虚拟账号每天十几条帖子会混进「新增帖子」；</li>
 *   <li>付费只算真金白银：{@code PAID} 且非纯 PawCoin，混合支付取现金段 —— PawCoin 在充值时已计一次收入，
 *       消费时再算就重复了；</li>
 *   <li>电商订单 = 当日下单且已付款（排除待付款 / 已取消），GMV 取订单总额（含 PawCoin 抵扣）。</li>
 * </ul>
 */
@Component
public class DailyReportQuery {

    private static final String REAL = SyntheticAccountSql.EXCLUDE_WHERE;

    static final String SQL_NEW_USERS =
            "SELECT count(*) FROM users u WHERE " + day("u.created_at") + " = ? AND " + REAL;

    static final String SQL_DAU =
            "SELECT count(*) FROM user_active_days d JOIN users u ON u.id = d.user_id AND " + REAL
                    + " WHERE d.active_date = ?";

    /** 逐日活跃记录的起始日（上线当天）；表为空 → null。 */
    static final String SQL_DAU_SINCE = "SELECT min(active_date) FROM user_active_days";

    static final String SQL_NEW_POSTS =
            "SELECT count(*) FROM content_posts p JOIN users u ON u.id = p.author_id AND " + REAL
                    + " WHERE " + day("p.created_at") + " = ? AND " + VISIBLE_POST_WHERE;

    static final String SQL_COMMENTS =
            "SELECT count(*) FROM comments c JOIN users u ON u.id = c.author_id AND " + REAL
                    + " WHERE " + day("c.created_at") + " = ? AND " + VALID_COMMENT_WHERE;

    static final String SQL_LIKES =
            "SELECT (SELECT count(*) FROM content_likes l JOIN users u ON u.id = l.user_id AND " + REAL
                    + " WHERE " + day("l.created_at") + " = ?)"
                    + " + (SELECT count(*) FROM comment_likes l JOIN users u ON u.id = l.user_id AND " + REAL
                    + " WHERE " + day("l.created_at") + " = ?)";

    static final String SQL_PAYMENTS =
            "SELECT count(*) AS orders, count(DISTINCT pi.user_id) AS users,"
                    + " coalesce(sum(CASE WHEN pi.channel = 'MIXED' THEN pi.cash_amount ELSE pi.amount END), 0) AS amount"
                    + " FROM payment_intents pi JOIN users u ON u.id = pi.user_id AND " + REAL
                    + " WHERE pi.status = 'PAID' AND pi.channel <> 'PAWCOIN' AND " + day("pi.updated_at") + " = ?";

    static final String SQL_SHOP =
            "SELECT count(*) AS orders, coalesce(sum(o.total_amount), 0) AS gmv"
                    + " FROM shop_orders o JOIN users u ON u.id = o.user_id AND " + REAL
                    + " WHERE " + day("o.created_at") + " = ? AND o.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')";

    private final JdbcTemplate jdbc;

    public DailyReportQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DailyReport.Metrics metricsOf(LocalDate d) {
        Map<String, Object> pay = jdbc.queryForMap(SQL_PAYMENTS, d);
        Map<String, Object> shop = jdbc.queryForMap(SQL_SHOP, d);
        return new DailyReport.Metrics(
                count(SQL_NEW_USERS, d),
                dauOf(d),
                count(SQL_NEW_POSTS, d),
                count(SQL_COMMENTS, d),
                count(SQL_LIKES, d, d),
                num(pay.get("orders")),
                num(pay.get("users")),
                num(pay.get("amount")),
                num(shop.get("orders")),
                num(shop.get("gmv")));
    }

    /**
     * 日活。只有「逐日记录起始日之后」的日子才算数：起始日当天是从部署那一刻才开始记的，
     * 不完整；更早的日子根本没有记录。这两种都返回 null（卡片上显示「—」），不拿残缺数冒充。
     */
    private Long dauOf(LocalDate d) {
        LocalDate since = jdbc.queryForObject(SQL_DAU_SINCE, LocalDate.class);
        if (since == null || !d.isAfter(since)) {
            return null;
        }
        return count(SQL_DAU, d);
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}
