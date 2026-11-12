package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.analytics.rollup.BusinessPlacementReader.ProductStock;
import com.tailtopia.analytics.rollup.ProductFamily.Row;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.SqlProvider;

/**
 * L0：指标族 product（analytics-pipeline Story 4.6 T4 / T5 / T7）——{@code product.sql} 可读、分段、不含当前日期函数、只用约定参数；
 * 库存快照只首次写（ON CONFLICT 不更新快照列）；近 30 天区间；准备（事务外读业务库）→ 计算（事务内临时表）；注册。全部 mock，无库。
 */
class ProductFamilyTest {

    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-z_][a-z0-9_]*)");
    private static final Instant RUN_AT = Instant.parse("2026-11-21T20:00:00Z");
    private static final Instant CAPTURED = Instant.parse("2026-11-21T20:00:05Z");

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static RollupContext ctx(JdbcTemplate jdbc, LocalDate asOf) {
        return new RollupContext(asOf, RUN_AT, List.of(), AnalyticsRollupProperties.defaults(), jdbc);
    }

    // ---------------------------------------------------------------- SQL 文件

    @Test
    void sqlHasNoCurrentDateAndUsesOnlyParameters() {
        String sql = SqlRollupFamily.readScript(ProductFamily.NAME);
        String lower = sql.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("now()", "current_date", "current_timestamp", "localtimestamp",
                "clock_timestamp", "transaction_timestamp", "statement_timestamp");
        assertThat(lower).doesNotContain("date(event_time", "event_time::date", "received_at", "at time zone");
        assertThat(lower).contains("v_events_person", "tmp_excluded_users", "is_production", "not p.is_suspicious",
                "p.source = 'app'", "x.user_id is null", "p.event_date = cast(:stat_date as date)");
        Map<String, List<String>> sections = RollupSqlSections.load(ProductFamily.NAME);
        assertThat(sections).containsOnlyKeys("compute", "daily", "unknown");
        for (List<String> stmts : sections.values()) {
            for (String stmt : stmts) {
                Matcher m = PARAM.matcher(stmt);
                while (m.find()) {
                    assertThat(Set.of("stat_date", "computed_at")).as("product 参数 :%s", m.group(1))
                            .contains(m.group(1));
                }
                assertThat(stmt).doesNotContain("shop_orders", "shop_order_lines", "sku_inventory", "shop_products");
            }
        }
        String compute = String.join("\n", sections.get("compute"));
        assertThat(compute).contains("count(*) FILTER (WHERE p.event = 'toko_product_detail_viewed')",
                "count(*) FILTER (WHERE p.event = 'toko_add_to_cart_tapped')",
                "GROUP BY p.properties ->> 'product_token'");
    }

    /** AC8：回补重算不覆盖已记录的库存快照——ON CONFLICT 只更新计数列、paid_qty_30d、computed_at；行集合以业务库为准。 */
    @Test
    void upsertKeepsStockSnapshot() {
        List<String> daily = RollupSqlSections.load(ProductFamily.NAME).get("daily");
        assertThat(daily).hasSize(1);
        String sql = daily.get(0);
        assertThat(sql).startsWith("INSERT INTO agg_daily_product (stat_date, product_token,")
                .contains("FROM tmp_product_snapshot s\nLEFT JOIN tmp_product_counts c", "s.sellable_stock = 0",
                        "COALESCE(c.detail_views, 0)", "ON CONFLICT (stat_date, product_token) DO UPDATE SET");
        String set = sql.substring(sql.indexOf("DO UPDATE SET"));
        assertThat(set).contains("detail_views = EXCLUDED.detail_views", "add_to_cart = EXCLUDED.add_to_cart",
                "paid_qty_30d = EXCLUDED.paid_qty_30d", "computed_at = EXCLUDED.computed_at");
        assertThat(set).doesNotContain("sellable_stock", "is_sold_out", "stock_captured_at", "product_name",
                "is_active");
        String cols = sql.substring(0, sql.indexOf(')') + 1);
        assertThat(cols).doesNotContain("user_id", "anonymous_id", "person_key");
        assertThat(RollupSqlSections.load(ProductFamily.NAME).get("unknown")).singleElement()
                .satisfies(s -> assertThat(s).startsWith("SELECT count(*)").contains("WHERE s.product_token IS NULL"));
    }

    // ---------------------------------------------------------------- 纯计算

    /** 近 30 天：paid_at ∈ [统计日 − 29 的 0 点 WIB, 统计日 + 1 的 0 点 WIB)。 */
    @Test
    void paidWindow() {
        assertThat(ProductFamily.paidFrom(d("2026-11-30"))).isEqualTo(Instant.parse("2026-10-31T17:00:00Z"));
        assertThat(ProductFamily.paidTo(d("2026-11-30"))).isEqualTo(Instant.parse("2026-11-30T17:00:00Z"));
    }

    /** 重算 [Y − 2, Y]。 */
    @Test
    void recomputeRange() {
        ProductFamily f = new ProductFamily(mock(BusinessPlacementReader.class));
        assertThat(f.recomputeDays()).isEqualTo(3);
        assertThat(RollupService.startDate(d("2026-11-19"), d("2026-11-20"), f.recomputeDays()))
                .isEqualTo(d("2026-11-18"));
        assertThat(f.hasPeriods()).isFalse();
    }

    @Test
    void rowsJoinPaidQtyAndDefaultZero() {
        List<Row> rows = ProductFamily.rows(List.of(new ProductStock("p1", "Royal", true, 5),
                new ProductStock("p2", "x".repeat(130), false, 0)), Map.of("p1", 7L, "gone", 3L));
        assertThat(rows).containsExactly(new Row("p1", "Royal", true, 5, 7), new Row("p2", "x".repeat(120), false, 0,
                0));
    }

    // ---------------------------------------------------------------- 注册

    @Test
    void registeredInConfig() {
        RollupFamily f = new RollupConfig().productRollupFamily(mock(BusinessPlacementReader.class));
        assertThat(f).isInstanceOf(ProductFamily.class);
        assertThat(f.name()).isEqualTo("product");
    }

    // ---------------------------------------------------------------- 准备（事务外）→ 计算（事务内）

    @Test
    void prepareReadsBusinessOutsideAndComputeFillsTemp() {
        LocalDate day = d("2026-11-20");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(reader.productStocks()).thenReturn(List.of(new ProductStock("p1", "Royal", true, 0)));
        when(reader.paidQtyByProduct(any(), any())).thenReturn(Map.of("p1", 2L));
        ProductFamily f = new ProductFamily(reader, Clock.fixed(CAPTURED, ZoneOffset.UTC));
        RollupContext ctx = ctx(jdbc, day);
        assertThatThrownBy(() -> f.computeDay(day, day, ctx)).isInstanceOf(IllegalStateException.class);

        f.prepareDay(day, day, ctx);
        verify(reader).paidQtyByProduct(Instant.parse("2026-10-21T17:00:00Z"), Instant.parse("2026-11-20T17:00:00Z"));
        verify(jdbc, never()).execute(anyString());

        f.computeDay(day, day, ctx);
        verify(jdbc).execute(ProductFamily.CREATE_SNAPSHOT_SQL);
        verify(jdbc).update(eq(ProductFamily.FILL_SNAPSHOT_SQL), any(PreparedStatementSetter.class));
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(f.section("compute").size() + f.section("daily").size())).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(0)).startsWith("CREATE TEMP TABLE IF NOT EXISTS tmp_product_counts");
        assertThat(sqls.get(sqls.size() - 1)).startsWith("INSERT INTO agg_daily_product");
        assertThatThrownBy(() -> f.computeDay(day, day, ctx)).isInstanceOf(IllegalStateException.class);
    }

    /** 业务库没有商品 → 临时表照建（空），不插入。 */
    @Test
    void emptySnapshot() {
        LocalDate day = d("2026-11-20");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(reader.productStocks()).thenReturn(List.of());
        when(reader.paidQtyByProduct(any(), any())).thenReturn(Map.of());
        ProductFamily f = new ProductFamily(reader);
        RollupContext ctx = ctx(jdbc, day);
        f.prepareDay(day, day, ctx);
        f.computeDay(day, day, ctx);
        verify(jdbc).execute(ProductFamily.CREATE_SNAPSHOT_SQL);
        verify(jdbc, never()).update(eq(ProductFamily.FILL_SNAPSHOT_SQL), any(PreparedStatementSetter.class));
    }
}
