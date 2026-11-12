package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.analytics.rollup.BusinessPlacementReader.CartOrderLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * L0：指标族 cart（analytics-pipeline Story 4.6 T3 / T5 / T7）——{@code cart.sql} 可读、分段、不含当前日期函数与禁用写法、
 * 只用 Java 传入的参数、去向 / 缺字段处理的关键片段；观察窗与重算区间；准备（事务外读业务库）→ 计算（事务内临时表）；注册。全部 mock，无库。
 */
class CartFamilyTest {

    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-z_][a-z0-9_]*)");
    private static final Instant RUN_AT = Instant.parse("2026-11-21T20:00:00Z");
    private static final Set<String> ALLOWED = Set.of("stat_date", "window_end", "window_end_at", "observed_end_at",
            "is_final", "computed_at");

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static RollupContext ctx(JdbcTemplate jdbc, LocalDate asOf) {
        return new RollupContext(asOf, RUN_AT, List.of(), AnalyticsRollupProperties.defaults(), jdbc);
    }

    // ---------------------------------------------------------------- SQL 文件

    /** AC6：日期全部参数传入，SQL 内不出现当前日期函数；只按 event_date 定日；标准过滤；只用约定参数。 */
    @Test
    void sqlHasNoCurrentDateAndUsesOnlyParameters() {
        String sql = SqlRollupFamily.readScript(CartFamily.NAME);
        String lower = sql.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("now()", "current_date", "current_timestamp", "localtimestamp",
                "clock_timestamp", "transaction_timestamp", "statement_timestamp");
        assertThat(lower).doesNotContain("date(event_time", "event_time::date", "received_at", "at time zone");
        assertThat(lower).contains("v_events_person", "tmp_excluded_users", "event_date", "is_production",
                "not p.is_suspicious", "p.source = 'app'", "x.user_id is null");
        Map<String, List<String>> sections = RollupSqlSections.load(CartFamily.NAME);
        assertThat(sections).containsOnlyKeys("compute", "daily");
        for (List<String> stmts : sections.values()) {
            for (String stmt : stmts) {
                Matcher m = PARAM.matcher(stmt);
                while (m.find()) {
                    assertThat(ALLOWED).as("cart 参数 :%s", m.group(1)).contains(m.group(1));
                }
                // 两库之间不跨库：埋点库 SQL 里不出现业务库表名
                assertThat(stmt).doesNotContain("shop_orders", "shop_order_lines", "shop_skus", "shop_products");
            }
        }
    }

    /** 加购件、去向（取最早）、只认购物车订单、缺字段兜底、数量 / 单价取法。 */
    @Test
    void computeImplementsPiecesAndOutcomes() {
        String compute = String.join("\n", RollupSqlSections.load(CartFamily.NAME).get("compute"));
        assertThat(compute).contains(
                "p.event_date BETWEEN CAST(:stat_date AS date) AND CAST(:window_end AS date) + 1",
                "'toko_add_to_cart_tapped', 'toko_cart_item_removed', 'toko_cart_quantity_changed'",
                "length(p.properties ->> 'sku_token') > 0", "ON COMMIT DROP");
        // 数值属性先校验格式再转型（坏值 / 缺失不抛）
        assertThat(compute).contains("p.properties ->> 'qty' ~ '^[0-9]{1,6}$'",
                "p.properties ->> 'qty_after' ~ '^[0-9]{1,6}$'", "p.properties ->> 'unit_price' ~ '^[0-9]{1,12}$'");
        // 1. 件 = 同人同 SKU 当天第一条
        assertThat(compute).contains("SELECT DISTINCT ON (e.person_key, e.sku_token)",
                "count(*) OVER (PARTITION BY e.person_key, e.sku_token)",
                "e.event_date = CAST(:stat_date AS date)", "ORDER BY e.person_key, e.sku_token, e.event_time");
        // 2. 去向：移出按人 + SKU；订单按用户 + SKU、created_at ≥ t_add；都在观察窗内
        assertThat(compute).contains("e.person_key = i2.person_key AND e.sku_token = i2.sku_token",
                "c.user_id = i2.user_id AND c.sku_token = i2.sku_token", "i2.user_id IS NOT NULL",
                "c.created_at >= i2.t_add", "c.created_at < CAST(:window_end_at AS timestamptz)",
                "e.event_time < CAST(:window_end_at AS timestamptz)", "c.paid_at IS NOT NULL AS paid");
        assertThat(compute).contains("'PURCHASED'", "'ORDERED_UNPAID'", "'REMOVED'", "'IN_CART'",
                "GREATEST(CAST(:observed_end_at AS timestamptz), i.t_add)");
        // 5. 缺 product_token：同 SKU 其他事件 → 订单行
        assertThat(compute).contains("WHERE i.product_token IS NULL", "FROM tmp_cart_orders o");
        // 4. 数量取窗口内最后一次 qty_after，再加购 qty，再 1；单价缺按 0
        assertThat(compute).contains("e.event = 'toko_cart_quantity_changed'", "ORDER BY e.event_time DESC",
                "i.add_qty,\n        1)");
        List<String> stmts = RollupSqlSections.load(CartFamily.NAME).get("compute");
        assertThat(stmts.get(stmts.size() - 1)).startsWith("UPDATE tmp_cart_items i\nSET dwell_minutes")
                .endsWith("\n        0)");
    }

    /** 先删当天再插；PRODUCT / TOTAL 两种行；写入列不含可识别个人的字段；按商品的行不写 carts。 */
    @Test
    void dailyDeletesThenInsertsAggregatesOnly() {
        List<String> daily = RollupSqlSections.load(CartFamily.NAME).get("daily");
        assertThat(daily).hasSize(2);
        assertThat(daily.get(0)).isEqualTo("DELETE FROM agg_daily_cart WHERE stat_date = CAST(:stat_date AS date)");
        String ins = daily.get(1);
        assertThat(ins).startsWith("INSERT INTO agg_daily_cart (stat_date, scope, product_token,");
        String cols = ins.substring(0, ins.indexOf(')') + 1);
        assertThat(cols).doesNotContain("user_id", "anonymous_id", "person_key");
        assertThat(ins).contains("SELECT 'PRODUCT' AS scope", "WHERE i.product_token IS NOT NULL",
                "SELECT 'TOTAL' AS scope", "'' AS product_token", "CAST(NULL AS bigint) AS carts",
                "count(DISTINCT i.person_key) AS carts", "round(g.add_items::numeric / NULLIF(g.carts, 0), 2)",
                "percentile_disc(0.5) WITHIN GROUP (ORDER BY i.dwell_minutes)",
                "sum(i.unit_price * i.final_qty)", "i.removed_reason = 'manual'",
                "i.removed_reason = 'invalid_cleared'", "CAST(:is_final AS boolean)",
                // 复审 R1：客户端上报的单价 / 数量求和封顶，伪造大值不能让整族汇总溢出失败
                "LEAST(g.cart_value_idr, 9223372036854775807)", "999999.99)");
    }

    // ---------------------------------------------------------------- 观察窗 / 重算区间

    @Test
    void windowAndFinal() {
        LocalDate day = d("2026-11-10");
        assertThat(CartFamily.windowEnd(day)).isEqualTo(d("2026-11-17"));
        // 窗口终点 = 11-18 0 点 WIB
        assertThat(CartFamily.windowEndAt(day)).isEqualTo(Instant.parse("2026-11-17T17:00:00Z"));
        // asOf 11-20：窗口已关闭，截到窗口终点
        assertThat(CartFamily.observedEndAt(day, d("2026-11-20"))).isEqualTo(Instant.parse("2026-11-17T17:00:00Z"));
        assertThat(CartFamily.isFinal(day, d("2026-11-17"))).isTrue();
        assertThat(CartFamily.isFinal(day, d("2026-11-16"))).isFalse();
        // asOf 11-12：数据只到 11-13 0 点 WIB
        assertThat(CartFamily.observedEndAt(day, d("2026-11-12"))).isEqualTo(Instant.parse("2026-11-12T17:00:00Z"));
        // 订单读取起点：加购早于当天 0 点（按收到时间归日的边角）时放宽
        assertThat(CartFamily.ordersFrom(day, null)).isEqualTo(Instant.parse("2026-11-09T17:00:00Z"));
        assertThat(CartFamily.ordersFrom(day, Instant.parse("2026-11-09T16:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-09T16:00:00Z"));
        assertThat(CartFamily.ordersFrom(day, Instant.parse("2026-11-10T01:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-09T17:00:00Z"));
    }

    /** 每次运行重算 [Y − 7, Y]；进度落后时从断点续算。 */
    @Test
    void recomputeRange() {
        CartFamily f = new CartFamily(mock(BusinessPlacementReader.class));
        assertThat(f.recomputeDays()).isEqualTo(8);
        LocalDate y = d("2026-11-20");
        assertThat(RollupService.startDate(y.minusDays(1), y, f.recomputeDays())).isEqualTo(d("2026-11-13"));
        assertThat(RollupService.startDate(null, y, f.recomputeDays())).isEqualTo(d("2026-11-13"));
        assertThat(RollupService.startDate(d("2026-11-05"), y, f.recomputeDays())).isEqualTo(d("2026-11-06"));
        assertThat(f.hasPeriods()).isFalse();
        assertThat(f.dependsOn()).isEmpty();
    }

    @Test
    void params() {
        MapSqlParameterSource p = CartFamily.params(d("2026-11-10"), d("2026-11-12"), RUN_AT);
        assertThat(p.getParameterNames()).containsExactlyInAnyOrderElementsOf(ALLOWED);
        assertThat(p.getValue("stat_date")).isEqualTo(d("2026-11-10"));
        assertThat(p.getValue("window_end")).isEqualTo(d("2026-11-17"));
        assertThat(((OffsetDateTime) p.getValue("window_end_at")).toInstant())
                .isEqualTo(Instant.parse("2026-11-17T17:00:00Z"));
        assertThat(((OffsetDateTime) p.getValue("observed_end_at")).toInstant())
                .isEqualTo(Instant.parse("2026-11-12T17:00:00Z"));
        assertThat(p.getValue("is_final")).isEqualTo(false);
    }

    // ---------------------------------------------------------------- 注册

    @Test
    void registeredInConfig() {
        RollupFamily f = new RollupConfig().cartRollupFamily(mock(BusinessPlacementReader.class));
        assertThat(f).isInstanceOf(CartFamily.class);
        assertThat(f.name()).isEqualTo("cart");
    }

    // ---------------------------------------------------------------- 准备（事务外）→ 计算（事务内）

    @Test
    void prepareReadsBusinessOutsideAndComputeFillsTemp() {
        LocalDate day = d("2026-11-10");
        LocalDate asOf = d("2026-11-20");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(jdbc.queryForObject(CartFamily.FIRST_ADD_SQL, Timestamp.class, day))
                .thenReturn(Timestamp.from(Instant.parse("2026-11-10T01:00:00Z")));
        when(reader.cartOrderLines(any(), any())).thenReturn(List.of(
                new CartOrderLine(1L, "sku1", "prod1", Instant.parse("2026-11-11T00:00:00Z"), null)));

        CartFamily f = new CartFamily(reader);
        RollupContext ctx = ctx(jdbc, asOf);
        assertThatThrownBy(() -> f.computeDay(day, asOf, ctx)).isInstanceOf(IllegalStateException.class);

        f.prepareDay(day, asOf, ctx);
        // [当日 0 点 WIB, 当日 + 8 天 0 点 WIB)
        verify(reader).cartOrderLines(Instant.parse("2026-11-09T17:00:00Z"), Instant.parse("2026-11-17T17:00:00Z"));
        verify(jdbc, never()).execute(org.mockito.ArgumentMatchers.anyString());

        f.computeDay(day, asOf, ctx);
        verify(jdbc).execute(CartFamily.CREATE_ORDERS_SQL);
        verify(jdbc).update(eq(CartFamily.FILL_ORDERS_SQL), any(PreparedStatementSetter.class));
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(f.section("compute").size() + f.section("daily").size())).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(0)).startsWith("CREATE TEMP TABLE IF NOT EXISTS tmp_cart_events");
        assertThat(sqls.get(sqls.size() - 1)).startsWith("INSERT INTO agg_daily_cart");
        // 一次 prepare 只能用一次
        assertThatThrownBy(() -> f.computeDay(day, asOf, ctx)).isInstanceOf(IllegalStateException.class);
    }

    /** 没有购物车订单 → 临时表照建（空），不插入。 */
    @Test
    void emptyOrdersStillCreatesTemp() {
        LocalDate day = d("2026-11-10");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(reader.cartOrderLines(any(), any())).thenReturn(List.of());
        CartFamily f = new CartFamily(reader);
        RollupContext ctx = ctx(jdbc, d("2026-11-20"));
        f.prepareDay(day, d("2026-11-20"), ctx);
        f.computeDay(day, d("2026-11-20"), ctx);
        verify(jdbc).execute(CartFamily.CREATE_ORDERS_SQL);
        verify(jdbc, never()).update(eq(CartFamily.FILL_ORDERS_SQL), any(PreparedStatementSetter.class));
    }

    /** prepare 的 key 与 compute 的日期不符 → 失败（防止串用业务数据）。 */
    @Test
    void mismatchedPrepareFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(reader.cartOrderLines(any(), any())).thenReturn(List.of());
        CartFamily f = new CartFamily(reader);
        RollupContext ctx = ctx(jdbc, d("2026-11-20"));
        f.prepareDay(d("2026-11-10"), d("2026-11-20"), ctx);
        assertThatThrownBy(() -> f.computeDay(d("2026-11-11"), d("2026-11-20"), ctx))
                .isInstanceOf(IllegalStateException.class);
    }
}
