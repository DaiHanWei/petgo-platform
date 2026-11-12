package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.BusinessPlacementReader.PaidOrder;
import com.tailtopia.analytics.rollup.PlacementTokoRule.Target;
import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import com.tailtopia.analytics.store.EventTimeCorrector;
import java.time.Duration;
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
 * 指标族 {@code placement}（analytics-pipeline Story 4.4 T4，S5 / FR-17 / FR-22，D-12 / D-13）：运营位曝光、点击、点击人数、
 * 点击后 7 天已付款 Toko 订单数，写 {@code agg_daily_placement} / {@code agg_weekly_placement} / {@code agg_monthly_placement}。
 *
 * <h2>跨库匹配（两库之间不做跨库 SQL）</h2>
 * 每个单元：
 * <ol>
 *   <li>{@link #prepareDay} / {@link #preparePeriods}（埋点库事务<b>之外</b>）：埋点库短查询当期的运营位与点击过的用户（归属后的
 *       {@code person_user_id}，非空）→ 业务库窄查询运营位配置（判定是否推广 Toko，{@link PlacementTokoRule}）与这些用户在
 *       {@code [点击日 0 点 WIB, 点击日 + 8 天 0 点 WIB)} 内的已付款订单。</li>
 *   <li>{@link #computeDay} / {@link #computePeriods}（单元事务内）：建 {@code tmp_placement_config}、{@code tmp_paid_orders}
 *       （{@code ON COMMIT DROP}），批量插入后执行 {@code analytics/rollup/placement.sql}。</li>
 * </ol>
 *
 * <h2>重算</h2>
 * {@link #recomputeDays()} = 10：7 天带单窗口 + 3 天晚到（D-6）。周 / 月：与重算窗口相交的周期用 {@link RollupPeriods} 取，
 * 周期内重新从明细去重（D-12，不是日表相加），{@code is_complete = 周期末 ≤ asOf}；周期首日早于汇总下限（明细已清理）的不算。
 *
 * <p>日志只打条数、日期、耗时，不打用户标识与订单 token。
 */
public class PlacementFamily implements RollupFamily {

    private static final Logger log = LoggerFactory.getLogger(PlacementFamily.class);

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "placement";
    /** 7 天带单窗口 + 3 天晚到。 */
    public static final int RECOMPUTE_DAYS = 10;
    /** 带单窗口：点击后 7 天。 */
    static final Duration ORDER_WINDOW = Duration.ofDays(7);

    /** 曝光事件（与 placement.sql 一致；3.1 若改事件名以 event-catalog.yaml 为准）。 */
    public static final List<String> IMPRESSION_EVENTS =
            List.of("ops_popup_shown", "toko_banner_shown", "toko_newbie_coupon_popup_shown");
    /** 点击事件（toko_newbie_coupon_popup_tapped 只算 action = 'register'）。 */
    public static final List<String> CLICK_EVENTS =
            List.of("ops_popup_tapped", "toko_banner_tapped", "toko_newbie_coupon_popup_tapped");

    static final String CREATE_CONFIG_SQL = "CREATE TEMP TABLE tmp_placement_config (placement_type varchar(32) NOT NULL, "
            + "placement_token varchar(64) NOT NULL, promotes_toko boolean, "
            + "PRIMARY KEY (placement_type, placement_token)) ON COMMIT DROP";
    static final String FILL_CONFIG_SQL = "INSERT INTO tmp_placement_config (placement_type, placement_token, promotes_toko) "
            + "SELECT * FROM unnest(?::text[], ?::text[], ?::boolean[])";
    static final String CREATE_ORDERS_SQL = "CREATE TEMP TABLE tmp_paid_orders (user_id bigint NOT NULL, "
            + "order_token varchar(32) NOT NULL, paid_at timestamptz NOT NULL) ON COMMIT DROP";
    static final String FILL_ORDERS_SQL = "INSERT INTO tmp_paid_orders (user_id, order_token, paid_at) "
            + "SELECT u, t, p::timestamptz FROM unnest(?::bigint[], ?::text[], ?::text[]) AS x(u, t, p)";

    /**
     * 埋点库短查询①：当期出现过的运营位（只看事件名与属性，含将被排除的用户——多查几个 token 无害）与按「是否合法」分的条数。
     * 合法 = 属性 placement_type 与事件名一致、placement_token 为 1–64 字符。新人券点击只看 action = 'register'。
     */
    static final String SCAN_PLACEMENTS_SQL = """
            SELECT d.event,
                   d.properties ->> 'placement_type' AS placement_type,
                   d.properties ->> 'placement_token' AS placement_token,
                   count(*) AS n
            FROM events_detail d
            WHERE d.event_date BETWEEN ? AND ?
              AND d.event IN ('ops_popup_shown', 'ops_popup_tapped', 'toko_banner_shown', 'toko_banner_tapped',
                              'toko_newbie_coupon_popup_shown', 'toko_newbie_coupon_popup_tapped')
              AND (d.event <> 'toko_newbie_coupon_popup_tapped' OR d.properties ->> 'action' = 'register')
              AND d.is_production
              AND NOT d.is_suspicious
              AND d.source = 'APP'
            GROUP BY 1, 2, 3""";

    /** 埋点库短查询②：当期点击过的、归属得到用户的人，及其最早 / 最晚点击时间。 */
    static final String SCAN_CLICKERS_SQL = """
            SELECT p.person_user_id, min(p.event_time) AS first_click, max(p.event_time) AS last_click
            FROM v_events_person p
            WHERE p.event_date BETWEEN ? AND ?
              AND p.event IN ('ops_popup_tapped', 'toko_banner_tapped', 'toko_newbie_coupon_popup_tapped')
              AND (p.event <> 'toko_newbie_coupon_popup_tapped' OR p.properties ->> 'action' = 'register')
              AND p.is_production
              AND NOT p.is_suspicious
              AND p.source = 'APP'
              AND p.person_user_id IS NOT NULL
            GROUP BY p.person_user_id""";

    /** 准备好的业务数据（只活在 prepare → compute 之间）。 */
    record Prepared(String key, List<ConfigRow> config, List<PaidOrder> orders, List<Period> periods) {
    }

    /** 一行运营位配置判定结果。 */
    record ConfigRow(String placementType, String placementToken, Boolean promotesToko) {
    }

    /** 一行扫描结果。 */
    record ScanRow(String event, String placementType, String placementToken, long count) {
    }

    private final BusinessPlacementReader reader;
    private final List<String> compute;
    private final List<String> daily;
    private final List<String> weekly;
    private final List<String> monthly;
    /** prepare 写、compute 读（{@link RollupService} 在 JVM 运行锁下串行调用）。 */
    private Prepared pending;

    public PlacementFamily(BusinessPlacementReader reader) {
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
        pending = prepare(dayKey(statDate), statDate, statDate, List.of(), ctx);
    }

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        Prepared p = take(dayKey(statDate));
        fillTemps(ctx.jdbcTemplate(), p);
        MapSqlParameterSource params = periodParams(statDate, statDate, asOf, ctx);
        run(ctx, compute, params);
        run(ctx, daily, params);
    }

    // ---------------------------------------------------------------- 周 / 月

    @Override
    public void preparePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, RollupContext ctx) {
        pending = null;
        List<Period> periods = computablePeriods(windowStart, windowEnd, asOf, ctx.properties().retentionDays());
        if (periods.isEmpty()) {
            pending = new Prepared(periodsKey(windowStart, windowEnd), List.of(), List.of(), periods);
            return;
        }
        LocalDate from = periods.stream().map(Period::start).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = periods.stream().map(Period::end).max(LocalDate::compareTo).orElseThrow();
        if (to.isAfter(asOf)) {
            to = asOf;
        }
        pending = prepare(periodsKey(windowStart, windowEnd), from, to, periods, ctx);
    }

    @Override
    public void computePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, RollupContext ctx) {
        Prepared p = take(periodsKey(windowStart, windowEnd));
        if (p.periods().isEmpty()) {
            return;
        }
        fillTemps(ctx.jdbcTemplate(), p);
        for (Period period : p.periods()) {
            MapSqlParameterSource params = periodParams(period.start(), period.end(), asOf, ctx);
            run(ctx, compute, params);
            run(ctx, period.kind() == Kind.WEEK ? weekly : monthly, params);
        }
    }

    /**
     * 与重算窗口相交的周、月（升序，先周后月），去掉首日早于汇总下限（{@link RollupService#floor}：明细已清理）的周期，
     * 避免用残缺明细把已有终值改小。
     */
    static List<Period> computablePeriods(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf, int retentionDays) {
        LocalDate floor = RollupService.floor(asOf, retentionDays);
        List<Period> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (Period p : RollupPeriods.overlapping(kind, windowStart, windowEnd)) {
                if (p.start().isBefore(floor)) {
                    log.info("analytics rollup period skipped: family={} kind={} start={} floor={} (detail already "
                            + "purged)", NAME, kind, p.start(), floor);
                } else {
                    out.add(p);
                }
            }
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- 准备（事务外）

    private Prepared prepare(String key, LocalDate from, LocalDate to, List<Period> periods, RollupContext ctx) {
        long t0 = System.nanoTime();
        JdbcTemplate analytics = ctx.jdbcTemplate();
        List<ScanRow> scan = analytics.query(SCAN_PLACEMENTS_SQL, (rs, i) -> new ScanRow(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getLong(4)), from, to);
        long skipped = scan.stream().filter(r -> !valid(r)).mapToLong(ScanRow::count).sum();
        Set<List<String>> placements = new LinkedHashSet<>();
        for (ScanRow r : scan) {
            if (valid(r)) {
                placements.add(List.of(r.placementType(), r.placementToken()));
            }
        }
        List<ConfigRow> config = placements.isEmpty() ? List.of() : configRows(placements);

        Set<Long> excluded = new HashSet<>(ctx.excludedUserIds());
        Set<Long> users = new LinkedHashSet<>();
        Instant[] range = new Instant[2];
        analytics.query(SCAN_CLICKERS_SQL, rs -> {
            long uid = rs.getLong(1);
            if (excluded.contains(uid)) {
                return;
            }
            users.add(uid);
            Instant first = rs.getTimestamp(2).toInstant();
            Instant last = rs.getTimestamp(3).toInstant();
            range[0] = range[0] == null || first.isBefore(range[0]) ? first : range[0];
            range[1] = range[1] == null || last.isAfter(range[1]) ? last : range[1];
        }, from, to);
        List<PaidOrder> orders = users.isEmpty() ? List.of()
                : reader.paidOrders(users, orderFrom(from, range[0]), orderTo(to, range[1]));
        log.info("analytics rollup placement prepared: family={} from={} to={} placements={} skippedEvents={} "
                + "clickUsers={} paidOrders={} ms={}", NAME, from, to, placements.size(), skipped, users.size(),
                orders.size(), (System.nanoTime() - t0) / 1_000_000);
        return new Prepared(key, config, orders, periods);
    }

    /** 读业务库运营位配置并逐个判定是否推广 Toko（只为当期出现过的运营位生成行；查不到 → 空）。 */
    private List<ConfigRow> configRows(Set<List<String>> placements) {
        boolean needPopups = placements.stream().anyMatch(p -> PlacementTokoRule.OPS_POPUP.equals(p.get(0)));
        boolean needBanners = placements.stream().anyMatch(p -> PlacementTokoRule.TOKO_BANNER.equals(p.get(0)));
        Map<String, Target> popups = needPopups ? reader.opsPopupTargets() : Map.of();
        Map<String, Target> banners = needBanners ? reader.shopBannerTargets() : Map.of();
        List<ConfigRow> out = new ArrayList<>(placements.size());
        for (List<String> p : placements) {
            String type = p.get(0);
            String token = p.get(1);
            Target target = switch (type) {
                case PlacementTokoRule.OPS_POPUP -> popups.get(token);
                case PlacementTokoRule.TOKO_BANNER -> banners.get(token);
                default -> null;
            };
            out.add(new ConfigRow(type, token, PlacementTokoRule.promotesToko(type, target)));
        }
        return out;
    }

    /** 与 placement.sql 的事件过滤一致：属性 placement_type 与事件名对应、token 为 1–64 字符。 */
    static boolean valid(ScanRow r) {
        String token = r.placementToken();
        if (token == null || token.isEmpty() || token.length() > 64) {
            return false;
        }
        return expectedType(r.event()).equals(r.placementType());
    }

    /** 事件名 → 应带的 placement_type。 */
    static String expectedType(String event) {
        if (event == null) {
            return "";
        }
        if (event.startsWith("ops_popup_")) {
            return PlacementTokoRule.OPS_POPUP;
        }
        if (event.startsWith("toko_banner_")) {
            return PlacementTokoRule.TOKO_BANNER;
        }
        if (event.startsWith("toko_newbie_coupon_popup_")) {
            return PlacementTokoRule.NEWBIE_COUPON;
        }
        return "";
    }

    /** 订单查询起点 = min(首日 0 点 WIB, 最早点击时间)。 */
    static Instant orderFrom(LocalDate from, Instant firstClick) {
        Instant dayStart = from.atStartOfDay(EventTimeCorrector.WIB).toInstant();
        return firstClick != null && firstClick.isBefore(dayStart) ? firstClick : dayStart;
    }

    /**
     * 订单查询终点（不含）= max(末日 + 8 天 0 点 WIB, 最晚点击时间 + 7 天 + 1 微秒)：覆盖「点击时间 < paid_at ≤ 点击时间 + 7 天」，
     * 点击时间在当日之外（明细层按收到时间归日的边角）也不漏。
     */
    static Instant orderTo(LocalDate to, Instant lastClick) {
        Instant dayEnd = to.plusDays(8).atStartOfDay(EventTimeCorrector.WIB).toInstant();
        if (lastClick == null) {
            return dayEnd;
        }
        Instant byClick = lastClick.plus(ORDER_WINDOW).plusNanos(1_000);
        return byClick.isAfter(dayEnd) ? byClick : dayEnd;
    }

    // ---------------------------------------------------------------- 计算（单元事务内）

    /** 建临时表并批量插入（只用埋点库事务连接）。 */
    static void fillTemps(JdbcTemplate jdbc, Prepared p) {
        jdbc.execute(CREATE_CONFIG_SQL);
        if (!p.config().isEmpty()) {
            String[] types = p.config().stream().map(ConfigRow::placementType).toArray(String[]::new);
            String[] tokens = p.config().stream().map(ConfigRow::placementToken).toArray(String[]::new);
            Boolean[] toko = p.config().stream().map(ConfigRow::promotesToko).toArray(Boolean[]::new);
            jdbc.update(FILL_CONFIG_SQL, ps -> {
                ps.setArray(1, ps.getConnection().createArrayOf("text", types));
                ps.setArray(2, ps.getConnection().createArrayOf("text", tokens));
                ps.setArray(3, ps.getConnection().createArrayOf("boolean", toko));
            });
        }
        jdbc.execute(CREATE_ORDERS_SQL);
        if (!p.orders().isEmpty()) {
            Long[] users = p.orders().stream().map(PaidOrder::userId).toArray(Long[]::new);
            String[] tokens = p.orders().stream().map(PaidOrder::orderToken).toArray(String[]::new);
            String[] paid = p.orders().stream().map(o -> o.paidAt().toString()).toArray(String[]::new);
            jdbc.update(FILL_ORDERS_SQL, ps -> {
                ps.setArray(1, ps.getConnection().createArrayOf("bigint", users));
                ps.setArray(2, ps.getConnection().createArrayOf("text", tokens));
                ps.setArray(3, ps.getConnection().createArrayOf("text", paid));
            });
        }
    }

    /** 周期 SQL 参数：{@code period_start}、{@code period_end}、{@code as_of}、{@code computed_at}。 */
    static MapSqlParameterSource periodParams(LocalDate start, LocalDate end, LocalDate asOf, RollupContext ctx) {
        return new MapSqlParameterSource()
                .addValue("period_start", start)
                .addValue("period_end", end)
                .addValue("as_of", asOf)
                .addValue("computed_at", OffsetDateTime.ofInstant(ctx.runAt(), ZoneOffset.UTC));
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
            throw new IllegalStateException("placement: prepare 未在本单元之前执行（" + key + "）");
        }
        return p;
    }

    private static String dayKey(LocalDate d) {
        return "day:" + d;
    }

    private static String periodsKey(LocalDate from, LocalDate to) {
        return "periods:" + from + ":" + to;
    }

    /** 测试用：各段语句。 */
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
