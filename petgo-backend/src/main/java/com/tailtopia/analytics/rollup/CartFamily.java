package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.BusinessPlacementReader.CartOrderLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 指标族 {@code cart}（analytics-pipeline Story 4.6，电商 #7，D-13）：购物车「加购件」按加购日聚合的去向、停留时长分布、
 * 购物车数、每车商品数与总价值，写 {@code agg_daily_cart}（每个商品一行 + 全体一行，只存聚合、不存人）。
 * SQL 见 {@code analytics/rollup/cart.sql}（{@code compute} / {@code daily} 两段）。
 *
 * <h2>重算（7 天观察窗）</h2>
 * {@link #recomputeDays()} = 8：运行日 R、{@code Y = R − 1} 时重算 {@code [Y − 7, Y]} 每一天；{@code agg_progress} 落后时框架从断点续算。
 * {@code is_final} = 加购日 + 7 ≤ {@code Y}（观察窗已关闭）。没有周 / 月表：购物车数（去重人数）的周 / 月去重值用
 * {@code agg_weekly_toko} / {@code agg_monthly_toko} 的 {@code atc_users}（同一批人，D-12，Story 4.5 已落）。
 *
 * <h2>跨库（两库之间不做跨库 SQL）</h2>
 * {@link #prepareDay}（埋点库事务<b>之外</b>）：埋点库短查询当天最早一次加购时间（按收到时间归日的边角可能早于当天 0 点），再用
 * {@link BusinessPlacementReader#cartOrderLines} 读 {@code [min(当天 0 点 WIB, 最早加购), 加购日 + 8 天 0 点 WIB)} 内创建的购物车订单行。
 * {@link #computeDay}（单元事务内）：建 {@code tmp_cart_orders}（{@code ON COMMIT DROP}）并批量插入后执行 SQL。
 * prepare 结果按 key 校验后取用（{@link RollupService} 在 JVM 运行锁下串行调用 prepare → compute）。
 *
 * <p>日志只打条数、日期、耗时，不打用户标识与 token。失败由 {@link RollupService} 统一处理（进度不推进、TECH 告警）。
 */
public class CartFamily implements RollupFamily {

    private static final Logger log = LoggerFactory.getLogger(CartFamily.class);

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "cart";
    /** 观察窗天数：加购日之后再看 7 个 WIB 日。 */
    public static final int WINDOW_DAYS = 7;
    /** 每次重算 {@code [Y − 7, Y]} 共 8 天。 */
    public static final int RECOMPUTE_DAYS = WINDOW_DAYS + 1;

    static final String CREATE_ORDERS_SQL = "CREATE TEMP TABLE tmp_cart_orders (user_id bigint NOT NULL, "
            + "sku_token text NOT NULL, product_token text, created_at timestamptz NOT NULL, paid_at timestamptz) "
            + "ON COMMIT DROP";
    static final String FILL_ORDERS_SQL = "INSERT INTO tmp_cart_orders (user_id, sku_token, product_token, created_at, "
            + "paid_at) SELECT u, s, p, c::timestamptz, pd::timestamptz "
            + "FROM unnest(?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[]) AS x(u, s, p, c, pd)";
    /** 埋点库短查询：当天最早一次加购（只用来放宽订单读取起点；不经归属视图、不取人）。 */
    static final String FIRST_ADD_SQL = "SELECT min(event_time) FROM events_detail "
            + "WHERE event_date = ? AND event = 'toko_add_to_cart_tapped'";

    /** 准备好的业务数据（只活在 prepare → compute 之间）。 */
    record Prepared(String key, List<CartOrderLine> orders) {
    }

    private final BusinessPlacementReader reader;
    private final List<String> compute;
    private final List<String> daily;
    /** prepare 写、compute 读。 */
    private Prepared pending;

    public CartFamily(BusinessPlacementReader reader) {
        this.reader = reader;
        Map<String, List<String>> sections = RollupSqlSections.load(NAME);
        String where = SqlRollupFamily.resourcePath(NAME);
        this.compute = RollupSqlSections.require(sections, "compute", where);
        this.daily = RollupSqlSections.require(sections, "daily", where);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int recomputeDays() {
        return RECOMPUTE_DAYS;
    }

    // ---------------------------------------------------------------- 纯计算（L0）

    /** 观察窗最晚落到的 WIB 日 = 加购日 + 7。 */
    static LocalDate windowEnd(LocalDate statDate) {
        return statDate.plusDays(WINDOW_DAYS);
    }

    /** 观察窗终点（不含）= 加购日 + 8 天的 0 点 WIB。 */
    static Instant windowEndAt(LocalDate statDate) {
        return TokoFamily.wibStart(windowEnd(statDate).plusDays(1));
    }

    /** 数据截至时刻 = min(观察窗终点, 计算基准日 + 1 天的 0 点 WIB)：仍在车里的件按它截断停留时长。 */
    static Instant observedEndAt(LocalDate statDate, LocalDate asOf) {
        Instant windowEnd = windowEndAt(statDate);
        Instant dataEnd = TokoFamily.wibStart(asOf.plusDays(1));
        return dataEnd.isBefore(windowEnd) ? dataEnd : windowEnd;
    }

    /** 观察窗已关闭：加购日 + 7 ≤ asOf。 */
    static boolean isFinal(LocalDate statDate, LocalDate asOf) {
        return !windowEnd(statDate).isAfter(asOf);
    }

    /** 订单读取起点 = min(加购日 0 点 WIB, 当天最早加购时间)。 */
    static Instant ordersFrom(LocalDate statDate, Instant firstAdd) {
        Instant dayStart = TokoFamily.wibStart(statDate);
        return firstAdd != null && firstAdd.isBefore(dayStart) ? firstAdd : dayStart;
    }

    /** SQL 参数。 */
    static MapSqlParameterSource params(LocalDate statDate, LocalDate asOf, Instant runAt) {
        return new MapSqlParameterSource()
                .addValue("stat_date", statDate)
                .addValue("window_end", windowEnd(statDate))
                .addValue("window_end_at", OffsetDateTime.ofInstant(windowEndAt(statDate), ZoneOffset.UTC))
                .addValue("observed_end_at", OffsetDateTime.ofInstant(observedEndAt(statDate, asOf), ZoneOffset.UTC))
                .addValue("is_final", isFinal(statDate, asOf))
                .addValue("computed_at", OffsetDateTime.ofInstant(runAt, ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------- 准备（事务外）

    @Override
    public void prepareDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        pending = null;
        long t0 = System.nanoTime();
        Timestamp first = ctx.jdbcTemplate().queryForObject(FIRST_ADD_SQL, Timestamp.class, statDate);
        Instant from = ordersFrom(statDate, first == null ? null : first.toInstant());
        List<CartOrderLine> orders = reader.cartOrderLines(from, windowEndAt(statDate));
        log.info("analytics rollup cart prepared: family={} date={} cartOrderLines={} ms={}", NAME, statDate,
                orders.size(), (System.nanoTime() - t0) / 1_000_000);
        pending = new Prepared(key(statDate), List.copyOf(orders));
    }

    // ---------------------------------------------------------------- 计算（单元事务内）

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        Prepared p = take(key(statDate));
        fillOrders(ctx.jdbcTemplate(), p.orders());
        MapSqlParameterSource params = params(statDate, asOf, ctx.runAt());
        for (String sql : compute) {
            ctx.jdbc().update(sql, params);
        }
        for (String sql : daily) {
            ctx.jdbc().update(sql, params);
        }
    }

    /** 建 {@code tmp_cart_orders} 并批量插入（只用埋点库事务连接）。 */
    static void fillOrders(JdbcTemplate jdbc, List<CartOrderLine> orders) {
        jdbc.execute(CREATE_ORDERS_SQL);
        if (orders.isEmpty()) {
            return;
        }
        Long[] users = orders.stream().map(CartOrderLine::userId).toArray(Long[]::new);
        String[] skus = orders.stream().map(CartOrderLine::skuToken).toArray(String[]::new);
        String[] products = orders.stream().map(CartOrderLine::productToken).toArray(String[]::new);
        String[] created = orders.stream().map(o -> o.createdAt().toString()).toArray(String[]::new);
        String[] paid = orders.stream().map(o -> o.paidAt() == null ? null : o.paidAt().toString())
                .toArray(String[]::new);
        jdbc.update(FILL_ORDERS_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("bigint", users));
            ps.setArray(2, ps.getConnection().createArrayOf("text", skus));
            ps.setArray(3, ps.getConnection().createArrayOf("text", products));
            ps.setArray(4, ps.getConnection().createArrayOf("text", created));
            ps.setArray(5, ps.getConnection().createArrayOf("text", paid));
        });
    }

    private Prepared take(String key) {
        Prepared p = pending;
        pending = null;
        if (p == null || !p.key().equals(key)) {
            throw new IllegalStateException("cart: prepare 未在本单元之前执行（" + key + "）");
        }
        return p;
    }

    private static String key(LocalDate d) {
        return "day:" + d;
    }

    /** 各段语句（测试也用）。 */
    List<String> section(String name) {
        return switch (name) {
            case "compute" -> compute;
            case "daily" -> daily;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
