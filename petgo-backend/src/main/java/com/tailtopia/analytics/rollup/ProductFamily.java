package com.tailtopia.analytics.rollup;

import com.tailtopia.analytics.rollup.BusinessPlacementReader.ProductStock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 指标族 {@code product}（analytics-pipeline Story 4.6，电商 #8 / #9，D-13）：商品每天的详情浏览次数、加购次数，
 * 以及业务库的库存快照与近 30 天已付款件数，写 {@code agg_daily_product}。SQL 见 {@code analytics/rollup/product.sql}
 * （{@code compute} / {@code daily} / {@code unknown} 三段）。
 *
 * <h2>重算</h2>
 * {@link #recomputeDays()} = 3（D-6）：重算 {@code [Y − 2, Y]}。库存只有「此刻」值：{@code sellable_stock} / {@code is_sold_out} /
 * {@code stock_captured_at}（及名称、上下架）只在行首次插入时写，回补只更新计数列与 {@code paid_qty_30d}（SQL 的 ON CONFLICT 不含这些列）。
 *
 * <h2>跨库（两库之间不做跨库 SQL）</h2>
 * {@link #prepareDay}（埋点库事务<b>之外</b>）：{@link BusinessPlacementReader#productStocks} 读商品全集与可售库存，
 * {@link BusinessPlacementReader#paidQtyByProduct} 读 {@code paid_at ∈ [统计日 − 29 的 0 点 WIB, 统计日 + 1 的 0 点 WIB)} 的已付款件数。
 * {@link #computeDay}（单元事务内）：建 {@code tmp_product_snapshot}（{@code ON COMMIT DROP}）并批量插入后执行 SQL。
 *
 * <p>日志只打条数、日期、耗时。失败由 {@link RollupService} 统一处理（进度不推进、TECH 告警）。
 */
public class ProductFamily implements RollupFamily {

    private static final Logger log = LoggerFactory.getLogger(ProductFamily.class);

    /** 指标族名 = SQL 文件名。 */
    public static final String NAME = "product";
    /** 每次重算 {@code [Y − 2, Y]}（D-6）。 */
    public static final int RECOMPUTE_DAYS = 3;
    /** 近 N 天已付款件数的 N。 */
    public static final int PAID_WINDOW_DAYS = 30;
    /** {@code agg_daily_product.product_name} 的长度上限（业务库 name 是 60，防御性截断）。 */
    static final int NAME_MAX = 120;

    static final String CREATE_SNAPSHOT_SQL = "CREATE TEMP TABLE tmp_product_snapshot (product_token text PRIMARY KEY, "
            + "product_name text NOT NULL, is_active boolean NOT NULL, sellable_stock bigint NOT NULL, "
            + "paid_qty_30d bigint NOT NULL, stock_captured_at timestamptz NOT NULL) ON COMMIT DROP";
    static final String FILL_SNAPSHOT_SQL = "INSERT INTO tmp_product_snapshot (product_token, product_name, is_active, "
            + "sellable_stock, paid_qty_30d, stock_captured_at) SELECT t, n, a, s, q, CAST(? AS timestamptz) "
            + "FROM unnest(?::text[], ?::text[], ?::boolean[], ?::bigint[], ?::bigint[]) AS x(t, n, a, s, q)";

    /** 一个商品的快照行（写临时表用）。 */
    record Row(String productToken, String name, boolean active, long sellableStock, long paidQty30d) {
    }

    /** 准备好的业务数据（只活在 prepare → compute 之间）。 */
    record Prepared(String key, List<Row> rows, Instant capturedAt) {
    }

    private final BusinessPlacementReader reader;
    private final Clock clock;
    private final List<String> compute;
    private final List<String> daily;
    private final String unknown;
    /** prepare 写、compute 读。 */
    private Prepared pending;

    public ProductFamily(BusinessPlacementReader reader) {
        this(reader, Clock.systemUTC());
    }

    ProductFamily(BusinessPlacementReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
        Map<String, List<String>> sections = RollupSqlSections.load(NAME);
        String where = SqlRollupFamily.resourcePath(NAME);
        this.compute = RollupSqlSections.require(sections, "compute", where);
        this.daily = RollupSqlSections.require(sections, "daily", where);
        List<String> u = RollupSqlSections.require(sections, "unknown", where);
        if (u.size() != 1) {
            throw new IllegalStateException(where + "：@section unknown 只能有一条语句");
        }
        this.unknown = u.get(0);
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

    /** 近 30 天已付款件数的起点（含）= 统计日 − 29 的 0 点 WIB。 */
    static Instant paidFrom(LocalDate statDate) {
        return TokoFamily.wibStart(statDate.minusDays(PAID_WINDOW_DAYS - 1L));
    }

    /** 近 30 天已付款件数的终点（不含）= 统计日 + 1 的 0 点 WIB。 */
    static Instant paidTo(LocalDate statDate) {
        return TokoFamily.wibStart(statDate.plusDays(1));
    }

    /** 业务库快照 + 件数 → 临时表行（没有已付款的商品记 0；名称防御性截断）。 */
    static List<Row> rows(List<ProductStock> stocks, Map<String, Long> paidQty) {
        List<Row> out = new ArrayList<>(stocks.size());
        for (ProductStock s : stocks) {
            String name = s.name() == null ? "" : s.name();
            if (name.length() > NAME_MAX) {
                name = name.substring(0, NAME_MAX);
            }
            out.add(new Row(s.productToken(), name, s.active(), Math.max(0, s.sellableStock()),
                    paidQty.getOrDefault(s.productToken(), 0L)));
        }
        return out;
    }

    // ---------------------------------------------------------------- 准备（事务外）

    @Override
    public void prepareDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        pending = null;
        long t0 = System.nanoTime();
        List<ProductStock> stocks = reader.productStocks();
        Instant capturedAt = clock.instant();
        Map<String, Long> paid = reader.paidQtyByProduct(paidFrom(statDate), paidTo(statDate));
        List<Row> rows = rows(stocks, paid);
        log.info("analytics rollup product prepared: family={} date={} products={} paidProducts={} ms={}", NAME,
                statDate, rows.size(), paid.size(), (System.nanoTime() - t0) / 1_000_000);
        pending = new Prepared(key(statDate), List.copyOf(rows), capturedAt);
    }

    // ---------------------------------------------------------------- 计算（单元事务内）

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        Prepared p = take(key(statDate));
        fillSnapshot(ctx.jdbcTemplate(), p.rows(), p.capturedAt());
        MapSqlParameterSource params = params(statDate, ctx.runAt());
        for (String sql : compute) {
            ctx.jdbc().update(sql, params);
        }
        for (String sql : daily) {
            ctx.jdbc().update(sql, params);
        }
        List<Long> dropped = ctx.jdbc().queryForList(unknown, params, Long.class);
        if (!dropped.isEmpty() && dropped.get(0) != null && dropped.get(0) > 0) {
            log.info("analytics rollup product unknown tokens dropped: family={} date={} count={}", NAME, statDate,
                    dropped.get(0));
        }
    }

    /** SQL 参数：{@code stat_date}、{@code computed_at}。 */
    static MapSqlParameterSource params(LocalDate statDate, Instant runAt) {
        return new MapSqlParameterSource()
                .addValue("stat_date", statDate)
                .addValue("computed_at", OffsetDateTime.ofInstant(runAt, ZoneOffset.UTC));
    }

    /** 建 {@code tmp_product_snapshot} 并批量插入（只用埋点库事务连接）。 */
    static void fillSnapshot(JdbcTemplate jdbc, List<Row> rows, Instant capturedAt) {
        jdbc.execute(CREATE_SNAPSHOT_SQL);
        if (rows.isEmpty()) {
            return;
        }
        String[] tokens = rows.stream().map(Row::productToken).toArray(String[]::new);
        String[] names = rows.stream().map(Row::name).toArray(String[]::new);
        Boolean[] active = rows.stream().map(Row::active).toArray(Boolean[]::new);
        Long[] stock = rows.stream().map(Row::sellableStock).toArray(Long[]::new);
        Long[] paid = rows.stream().map(Row::paidQty30d).toArray(Long[]::new);
        OffsetDateTime at = OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC);
        jdbc.update(FILL_SNAPSHOT_SQL, ps -> {
            ps.setObject(1, at);
            ps.setArray(2, ps.getConnection().createArrayOf("text", tokens));
            ps.setArray(3, ps.getConnection().createArrayOf("text", names));
            ps.setArray(4, ps.getConnection().createArrayOf("boolean", active));
            ps.setArray(5, ps.getConnection().createArrayOf("bigint", stock));
            ps.setArray(6, ps.getConnection().createArrayOf("bigint", paid));
        });
    }

    private Prepared take(String key) {
        Prepared p = pending;
        pending = null;
        if (p == null || !p.key().equals(key)) {
            throw new IllegalStateException("product: prepare 未在本单元之前执行（" + key + "）");
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
            case "unknown" -> List.of(unknown);
            default -> throw new IllegalArgumentException(name);
        };
    }
}
