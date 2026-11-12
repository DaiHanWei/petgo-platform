package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.BusinessPlacementReader.PaidOrder;
import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import com.tailtopia.analytics.store.EventTimeCorrector;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 指标族 {@code toko}（analytics-pipeline Story 4.5，电商 #4 / #5 / #6、产品 #2，D-12 / D-13）：Toko 打开人数、7 步漏斗、
 * 已提交 / 已付款订单数、加购转化，写 {@code agg_daily_toko} / {@code agg_weekly_toko} / {@code agg_monthly_toko}。
 * SQL 见 {@code analytics/rollup/toko.sql}（日 / 周 / 月复用同一段 {@code compute}）。
 *
 * <h2>重算（AC5：依赖 7 天窗口的指标每次回补最近 7 天）</h2>
 * <ul>
 *   <li>日：{@link #recomputeDays()} = 8，即运行日 R、{@code Y = R − 1} 时重算 {@code [Y − 7, Y]}；{@code agg_progress} 落后时
 *       框架从断点续算（取较早者）。</li>
 *   <li>周 / 月：只算<b>已结束</b>、且结束日落在本轮重算区间内的周期（D-12「周期结束次日计算」，按 7 天窗口回补），
 *       周期内重新从明细去重；周期首日早于汇总下限（明细已清理）的不算。</li>
 *   <li>{@code is_final} = 周期末日 ≤ {@code Y − 7}（7 天窗口已全部关闭）。</li>
 * </ul>
 *
 * <h2>跨库（两库之间不做跨库 SQL）</h2>
 * {@link #prepareDay} / {@link #preparePeriods}（埋点库事务<b>之外</b>）：埋点库短查询本单元可能到达第 6 步或加购过的已归属用户 →
 * 复用 Story 4.4 的 {@link BusinessPlacementReader#paidOrders} 读这些用户在
 * {@code [首日 0 点 WIB, 末日 + 8 天 0 点 WIB)} 内的已付款订单（D-13：{@code paid_at} 非空，退款 / 取消仍算），再按周期读
 * {@code orders_paid} / {@code orders_created_biz} 两个计数（下单人 USER + REAL）。
 * {@link #computeDay} / {@link #computePeriods}（单元事务内）：建 {@code tmp_toko_paid_orders(user_id, paid_at)}
 * （{@code ON COMMIT DROP}）后执行 SQL。prepare 结果按 key 校验后取用。
 *
 * <p>日志只打条数、日期、耗时，不打用户标识。失败由 {@link RollupService} 统一处理（进度不推进、TECH 告警）。
 */
public class TokoFamily implements RollupFamily {

    private static final Logger log = LoggerFactory.getLogger(TokoFamily.class);

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "toko";
    /** 漏斗 / 加购转化窗口天数。 */
    public static final int WINDOW_DAYS = 7;
    /** 每次重算 {@code [Y − 7, Y]} 共 8 天。 */
    public static final int RECOMPUTE_DAYS = WINDOW_DAYS + 1;

    static final String CREATE_ORDERS_SQL = "CREATE TEMP TABLE tmp_toko_paid_orders (user_id bigint NOT NULL, "
            + "paid_at timestamptz NOT NULL) ON COMMIT DROP";
    static final String FILL_ORDERS_SQL = "INSERT INTO tmp_toko_paid_orders (user_id, paid_at) "
            + "SELECT u, p::timestamptz FROM unnest(?::bigint[], ?::text[]) AS x(u, p)";

    /**
     * 埋点库短查询：本单元可能用到已付款订单的已归属用户（第 6 步事件或加购），及其最早 / 最晚事件时间。
     * 区间与 toko.sql 的明细读取区间一致（{@code [首日, 末日 + 8]}），标准过滤同 SQL；排除用户在 Java 侧去掉。
     */
    static final String SCAN_USERS_SQL = """
            SELECT p.person_user_id, min(p.event_time) AS first_at, max(p.event_time) AS last_at
            FROM v_events_person p
            WHERE p.event_date BETWEEN ? AND ?
              AND p.event IN ('toko_add_to_cart_tapped', 'toko_order_pay_tapped', 'toko_order_payment_succeeded')
              AND p.is_production
              AND NOT p.is_suspicious
              AND p.source = 'APP'
              AND p.person_user_id IS NOT NULL
            GROUP BY p.person_user_id""";

    /** 一个周期：写入段（daily / weekly / monthly）与 WIB 起止日（含）。 */
    record Span(String section, LocalDate start, LocalDate end) {
    }

    /** 一个计算单元内的一个周期（日 / 周 / 月）与其业务库计数。 */
    record Unit(String section, LocalDate start, LocalDate end, long ordersPaid, long ordersCreated) {
    }

    /** 准备好的业务数据（只活在 prepare → compute 之间）。 */
    record Prepared(String key, List<PaidOrder> orders, List<Unit> units) {
    }

    private final BusinessPlacementReader reader;
    private final List<String> compute;
    private final List<String> daily;
    private final List<String> weekly;
    private final List<String> monthly;
    /** prepare 写、compute 读（{@link RollupService} 在 JVM 运行锁下串行调用）。 */
    private Prepared pending;

    public TokoFamily(BusinessPlacementReader reader) {
        this.reader = reader;
        Map<String, List<String>> sections = RollupSqlSections.load(NAME);
        String where = SqlRollupFamily.resourcePath(NAME);
        this.compute = RollupSqlSections.require(sections, "compute", where);
        this.daily = RollupSqlSections.require(sections, "daily", where);
        this.weekly = RollupSqlSections.require(sections, "weekly", where);
        this.monthly = RollupSqlSections.require(sections, "monthly", where);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int recomputeDays() {
        return RECOMPUTE_DAYS;
    }

    @Override
    public boolean hasPeriods() {
        return true;
    }

    // ---------------------------------------------------------------- 日

    @Override
    public void prepareDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        pending = null;
        pending = prepare(dayKey(statDate), List.of(new Span("daily", statDate, statDate)), ctx);
    }

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        execute(take(dayKey(statDate)), asOf, ctx);
    }

    // ---------------------------------------------------------------- 周 / 月

    @Override
    public void preparePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, RollupContext ctx) {
        pending = null;
        List<Span> spans = finishedPeriods(windowStart, windowEnd, asOf, ctx.properties().retentionDays()).stream()
                .map(p -> new Span(p.kind() == Kind.WEEK ? "weekly" : "monthly", p.start(), p.end()))
                .toList();
        pending = prepare(periodsKey(windowStart, windowEnd), spans, ctx);
    }

    @Override
    public void computePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, RollupContext ctx) {
        execute(take(periodsKey(windowStart, windowEnd)), asOf, ctx);
    }

    /**
     * 本轮要算的周、月（升序，先周后月）：已结束（末日 ≤ asOf）、末日落在 {@code [windowStart, windowEnd]} 内，
     * 且首日不早于汇总下限（{@link RollupService#floor}：明细已清理，避免用残缺明细把已有终值改小）。
     */
    static List<Period> finishedPeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf,
            int retentionDays) {
        LocalDate floor = RollupService.floor(asOf, retentionDays);
        List<Period> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (Period p : RollupPeriods.overlapping(kind, windowStart, windowEnd)) {
                if (p.end().isBefore(windowStart) || p.end().isAfter(windowEnd) || !p.isComplete(asOf)) {
                    continue;
                }
                if (p.start().isBefore(floor)) {
                    log.info("analytics rollup period skipped: family={} kind={} start={} floor={} (detail already "
                            + "purged)", NAME, kind, p.start(), floor);
                    continue;
                }
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /** 7 天窗口已全部关闭：周期末日 ≤ asOf − 7。 */
    static boolean isFinal(LocalDate periodEnd, LocalDate asOf) {
        return !periodEnd.isAfter(asOf.minusDays(WINDOW_DAYS));
    }

    /** 窗口最晚落到的 WIB 日 = 末日 + 7。 */
    static LocalDate windowEnd(LocalDate periodEnd) {
        return periodEnd.plusDays(WINDOW_DAYS);
    }

    /** WIB 日期 {@code d} 的 0 点（UTC 时刻）。 */
    static Instant wibStart(LocalDate d) {
        return d.atStartOfDay(EventTimeCorrector.WIB).toInstant();
    }

    // ---------------------------------------------------------------- 准备（事务外）

    private Prepared prepare(String key, List<Span> spans, RollupContext ctx) {
        if (spans.isEmpty()) {
            return new Prepared(key, List.of(), List.of());
        }
        long t0 = System.nanoTime();
        LocalDate from = spans.stream().map(Span::start).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = spans.stream().map(Span::end).max(LocalDate::compareTo).orElseThrow();

        Set<Long> excluded = new HashSet<>(ctx.excludedUserIds());
        Set<Long> users = new LinkedHashSet<>();
        Instant[] range = new Instant[2];
        ctx.jdbcTemplate().query(SCAN_USERS_SQL, rs -> {
            long uid = rs.getLong(1);
            if (excluded.contains(uid)) {
                return;
            }
            users.add(uid);
            Instant first = rs.getTimestamp(2).toInstant();
            Instant last = rs.getTimestamp(3).toInstant();
            range[0] = range[0] == null || first.isBefore(range[0]) ? first : range[0];
            range[1] = range[1] == null || last.isAfter(range[1]) ? last : range[1];
        }, from, windowEnd(to).plusDays(1));
        // 订单区间：[首日 0 点 WIB, 末日 + 8 天 0 点 WIB)，事件时间在区间外（按收到时间归日的边角）时放宽
        List<PaidOrder> orders = users.isEmpty() ? List.of()
                : reader.paidOrders(users, PlacementFamily.orderFrom(from, range[0]),
                        PlacementFamily.orderTo(to, range[1]));

        List<Unit> units = new ArrayList<>(spans.size());
        for (Span sp : spans) {
            Instant start = wibStart(sp.start());
            Instant end = wibStart(sp.end().plusDays(1));
            units.add(new Unit(sp.section(), sp.start(), sp.end(), reader.paidOrderCount(start, end),
                    reader.createdOrderCount(start, end)));
        }
        log.info("analytics rollup toko prepared: family={} from={} to={} units={} users={} paidOrders={} ms={}", NAME,
                from, to, units.size(), users.size(), orders.size(), (System.nanoTime() - t0) / 1_000_000);
        return new Prepared(key, orders, List.copyOf(units));
    }

    // ---------------------------------------------------------------- 计算（单元事务内）

    private void execute(Prepared p, LocalDate asOf, RollupContext ctx) {
        if (p.units().isEmpty()) {
            return;
        }
        fillOrders(ctx.jdbcTemplate(), p.orders());
        for (Unit u : p.units()) {
            MapSqlParameterSource params = params(u, asOf, ctx.runAt());
            run(ctx, compute, params);
            run(ctx, section(u.section()), params);
        }
    }

    /** 建 {@code tmp_toko_paid_orders} 并批量插入（只用埋点库事务连接）。 */
    static void fillOrders(JdbcTemplate jdbc, List<PaidOrder> orders) {
        jdbc.execute(CREATE_ORDERS_SQL);
        if (orders.isEmpty()) {
            return;
        }
        Long[] users = orders.stream().map(PaidOrder::userId).toArray(Long[]::new);
        String[] paid = orders.stream().map(o -> o.paidAt().toString()).toArray(String[]::new);
        jdbc.update(FILL_ORDERS_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("bigint", users));
            ps.setArray(2, ps.getConnection().createArrayOf("text", paid));
        });
    }

    /** SQL 参数：{@code period_start}、{@code period_end}、{@code window_end}、{@code is_final}、两个业务库计数、{@code computed_at}。 */
    static MapSqlParameterSource params(Unit u, LocalDate asOf, Instant runAt) {
        return new MapSqlParameterSource()
                .addValue("period_start", u.start())
                .addValue("period_end", u.end())
                .addValue("window_end", windowEnd(u.end()))
                .addValue("is_final", isFinal(u.end(), asOf))
                .addValue("orders_paid", Math.toIntExact(u.ordersPaid()))
                .addValue("orders_created_biz", Math.toIntExact(u.ordersCreated()))
                .addValue("computed_at", OffsetDateTime.ofInstant(runAt, ZoneOffset.UTC));
    }

    private static void run(RollupContext ctx, List<String> statements, MapSqlParameterSource params) {
        for (String sql : statements) {
            ctx.jdbc().update(sql, params);
        }
    }

    private Prepared take(String key) {
        Prepared p = pending;
        pending = null;
        if (p == null || !p.key().equals(key)) {
            throw new IllegalStateException("toko: prepare 未在本单元之前执行（" + key + "）");
        }
        return p;
    }

    private static String dayKey(LocalDate d) {
        return "day:" + d;
    }

    private static String periodsKey(LocalDate from, LocalDate to) {
        return "periods:" + from + ":" + to;
    }

    /** 各段语句（测试也用）。 */
    List<String> section(String name) {
        return switch (name) {
            case "compute" -> compute;
            case "daily" -> daily;
            case "weekly" -> weekly;
            case "monthly" -> monthly;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
