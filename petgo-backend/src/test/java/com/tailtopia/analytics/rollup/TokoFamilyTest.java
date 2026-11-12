package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.analytics.rollup.BusinessPlacementReader.PaidOrder;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
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
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.SqlProvider;

/**
 * L0：指标族 toko（analytics-pipeline Story 4.5 T3 / T4 / T6）——{@code toko.sql} 可读、分段、不含当前日期函数与禁用写法、
 * 只用 Java 传入的参数、口径关键片段；准备步骤（事务外读业务库）与单元事务内的临时表 / 语句顺序；注册。全部 mock，无库。
 */
class TokoFamilyTest {

    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-z_][a-z0-9_]*)");
    private static final Instant RUN_AT = Instant.parse("2026-11-21T20:00:00Z");
    private static final Set<String> ALLOWED = Set.of("period_start", "period_end", "window_end", "is_final",
            "orders_paid", "orders_created_biz", "computed_at");

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static RollupContext ctx(JdbcTemplate jdbc, LocalDate asOf, List<Long> excluded) {
        return new RollupContext(asOf, RUN_AT, excluded, AnalyticsRollupProperties.defaults(), jdbc);
    }

    // ---------------------------------------------------------------- SQL 文件

    /** AC7：日期全部参数传入，SQL 内不出现当前日期函数；只按 event_date 定日；标准过滤。 */
    @Test
    void sqlHasNoCurrentDateAndUsesOnlyParameters() {
        String sql = SqlRollupFamily.readScript("toko");
        String lower = sql.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("now()", "current_date", "current_timestamp", "localtimestamp",
                "clock_timestamp", "transaction_timestamp", "statement_timestamp");
        assertThat(lower).doesNotContain("date(event_time", "event_time::date", "received_at", "at time zone");
        assertThat(lower).contains("v_events_person", "tmp_excluded_users", "event_date", "is_production",
                "not p.is_suspicious", "p.source = 'app'", "x.user_id is null");
        Map<String, List<String>> sections = RollupSqlSections.load("toko");
        assertThat(sections).containsOnlyKeys("compute", "daily", "weekly", "monthly");
        for (List<String> stmts : sections.values()) {
            for (String stmt : stmts) {
                Matcher m = PARAM.matcher(stmt);
                while (m.find()) {
                    assertThat(ALLOWED).as("toko 参数 :%s", m.group(1)).contains(m.group(1));
                }
            }
        }
    }

    /** 明细读取区间 [period_start, window_end + 1]；漏斗 7 步与加购转化的关键判定。 */
    @Test
    void computeImplementsFunnelAndAddToCart() {
        String compute = String.join("\n", RollupSqlSections.load("toko").get("compute"));
        assertThat(compute).contains(
                "p.event_date BETWEEN CAST(:period_start AS date) AND CAST(:window_end AS date) + 1",
                "p.properties ->> 'screen' = 'toko_page'", "ON COMMIT DROP");
        assertThat(compute).doesNotContain("'/shop'", "toko_tab_viewed");
        for (String e : new String[] {"screen_viewed", "toko_product_shown", "toko_product_detail_viewed",
            "toko_buy_now_tapped", "toko_add_to_cart_tapped", "toko_order_submitted", "toko_order_pay_tapped",
            "toko_order_payment_succeeded"}) {
            assertThat(compute).contains("'" + e + "'");
        }
        // s6 认两个事件
        assertThat(compute).contains("WHEN 'toko_order_pay_tapped' THEN 6", "WHEN 'toko_order_payment_succeeded' THEN 6",
                "WHEN 'toko_buy_now_tapped' THEN 4", "WHEN 'toko_add_to_cart_tapped' THEN 4");
        // 组 = 周期内首次 s1；按顺序（≥ 上一步）、窗口 [t1, t1 + 7 天]
        assertThat(compute).contains("e.event_date BETWEEN CAST(:period_start AS date) AND CAST(:period_end AS date)",
                "min(e.event_time) AS t1");
        for (int k = 2; k <= 6; k++) {
            String prev = k == 2 ? "g.t1" : "f.t" + (k - 1);
            assertThat(compute).contains("e.step = " + k + "\n              AND e.event_time >= " + prev + " AND");
        }
        assertThat(compute).contains("e.event_time <= g.t1 + interval '7 days'",
                "e.event_time <= f.t1 + interval '7 days'");
        // s7：业务库已付款订单，同一用户、[t6, t1 + 7 天]；匿名人不通过
        assertThat(compute).contains("f.t6 IS NOT NULL AND f.user_id IS NOT NULL", "tmp_toko_paid_orders",
                "o.paid_at >= f.t6 AND o.paid_at <= f.t1 + interval '7 days'");
        // 加购转化 [t, t + 7 天]
        assertThat(compute).contains("o.paid_at >= e.event_time", "o.paid_at <= e.event_time + interval '7 days'");
        // 已提交订单数 = 事件条数
        assertThat(compute).contains("WHERE e.event = 'toko_order_submitted'");
    }

    /** upsert 到三张表；写入列不含可识别个人的字段；业务库计数与 is_final 来自参数。 */
    @Test
    void upsertSections() {
        Map<String, List<String>> s = RollupSqlSections.load("toko");
        String[][] targets = {{"daily", "agg_daily_toko", "stat_date"}, {"weekly", "agg_weekly_toko", "week_start"},
            {"monthly", "agg_monthly_toko", "month_start"}};
        for (String[] t : targets) {
            List<String> stmts = s.get(t[0]);
            assertThat(stmts).hasSize(1);
            String sql = stmts.get(0);
            assertThat(sql).startsWith("INSERT INTO " + t[1] + " (" + t[2] + ",")
                    .contains("ON CONFLICT (" + t[2] + ") DO UPDATE SET", "CAST(:orders_paid AS integer)",
                            "CAST(:orders_created_biz AS integer)", "CAST(:is_final AS boolean)",
                            "atc_converted_users = EXCLUDED.atc_converted_users", "is_final = EXCLUDED.is_final");
            String cols = sql.substring(0, sql.indexOf(')') + 1);
            assertThat(cols).doesNotContain("user_id", "anonymous_id", "person_key");
        }
    }

    // ---------------------------------------------------------------- 注册

    @Test
    void registeredInConfig() {
        RollupFamily f = new RollupConfig().tokoRollupFamily(mock(BusinessPlacementReader.class));
        assertThat(f).isInstanceOf(TokoFamily.class);
        assertThat(f.name()).isEqualTo("toko");
        assertThat(f.dependsOn()).isEmpty();
    }

    // ---------------------------------------------------------------- 准备（事务外）→ 计算（事务内）

    @SuppressWarnings("unchecked")
    @Test
    void prepareDayReadsBusinessOutsideAndComputeFillsTemp() {
        LocalDate day = d("2026-11-10");
        LocalDate asOf = d("2026-11-20");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            h.processRow(user(1L, "2026-11-10T03:00:00Z", "2026-11-10T05:00:00Z"));
            h.processRow(user(7L, "2026-11-10T04:00:00Z", "2026-11-10T04:00:00Z"));
            return null;
        }).when(jdbc).query(eq(TokoFamily.SCAN_USERS_SQL), any(RowCallbackHandler.class), eq(day),
                eq(d("2026-11-18")));
        when(reader.paidOrders(any(), any(), any())).thenReturn(
                List.of(new PaidOrder(1L, "o1", Instant.parse("2026-11-12T00:00:00Z"))));
        when(reader.paidOrderCount(any(), any())).thenReturn(3L);
        when(reader.createdOrderCount(any(), any())).thenReturn(4L);

        TokoFamily f = new TokoFamily(reader);
        RollupContext ctx = ctx(jdbc, asOf, List.of(7L));
        assertThatThrownBy(() -> f.computeDay(day, asOf, ctx)).isInstanceOf(IllegalStateException.class);

        f.prepareDay(day, asOf, ctx);
        ArgumentCaptor<Collection<Long>> users = ArgumentCaptor.forClass(Collection.class);
        // [当日 0 点 WIB, 当日 + 8 天 0 点 WIB)
        verify(reader).paidOrders(users.capture(), eq(Instant.parse("2026-11-09T17:00:00Z")),
                eq(Instant.parse("2026-11-17T17:00:00Z")));
        assertThat(users.getValue()).containsExactly(1L);
        verify(reader).paidOrderCount(Instant.parse("2026-11-09T17:00:00Z"), Instant.parse("2026-11-10T17:00:00Z"));
        verify(reader).createdOrderCount(Instant.parse("2026-11-09T17:00:00Z"),
                Instant.parse("2026-11-10T17:00:00Z"));
        verify(jdbc, never()).execute(anyString());

        f.computeDay(day, asOf, ctx);
        verify(jdbc).execute(TokoFamily.CREATE_ORDERS_SQL);
        verify(jdbc).update(eq(TokoFamily.FILL_ORDERS_SQL), any(PreparedStatementSetter.class));
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(f.section("compute").size() + 1)).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(0)).startsWith("CREATE TEMP TABLE IF NOT EXISTS tmp_toko_events");
        assertThat(sqls.get(sqls.size() - 1)).startsWith("INSERT INTO agg_daily_toko");
        assertThatThrownBy(() -> f.computeDay(day, asOf, ctx)).isInstanceOf(IllegalStateException.class);
    }

    /** 没有可能付款的已归属用户 → 不查订单明细，但订单计数照读；临时表照建（空）。 */
    @Test
    void prepareSkipsPaidOrdersWhenNoUsers() {
        LocalDate day = d("2026-11-10");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        TokoFamily f = new TokoFamily(reader);
        RollupContext ctx = ctx(jdbc, d("2026-11-20"), List.of());
        f.prepareDay(day, d("2026-11-20"), ctx);
        verify(reader, never()).paidOrders(any(), any(), any());
        verify(reader).paidOrderCount(any(), any());
        f.computeDay(day, d("2026-11-20"), ctx);
        verify(jdbc).execute(TokoFamily.CREATE_ORDERS_SQL);
        verify(jdbc, never()).update(eq(TokoFamily.FILL_ORDERS_SQL), any(PreparedStatementSetter.class));
    }

    /** 周期单元：一次准备（区间覆盖全部周期 + 7 天窗口），每个周期各读一次计数，逐周期 compute + weekly / monthly。 */
    @Test
    void periodsPrepareOnceAndRunEachFinishedPeriod() {
        LocalDate asOf = d("2026-12-02");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        TokoFamily f = new TokoFamily(reader);
        RollupContext ctx = ctx(jdbc, asOf, List.of());
        f.preparePeriods(d("2026-11-25"), asOf, asOf, ctx);
        // 周 11-23..11-29、月 11-01..11-30：明细区间 [11-01, 11-30 + 8]
        verify(jdbc).query(eq(TokoFamily.SCAN_USERS_SQL), any(RowCallbackHandler.class), eq(d("2026-11-01")),
                eq(d("2026-12-08")));
        verify(reader).paidOrderCount(Instant.parse("2026-11-22T17:00:00Z"), Instant.parse("2026-11-29T17:00:00Z"));
        verify(reader).paidOrderCount(Instant.parse("2026-10-31T17:00:00Z"), Instant.parse("2026-11-30T17:00:00Z"));
        f.computePeriods(d("2026-11-25"), asOf, asOf, ctx);
        verify(jdbc, times(1)).execute(TokoFamily.CREATE_ORDERS_SQL);
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        int perPeriod = f.section("compute").size() + 1;
        verify(jdbc, times(2 * perPeriod)).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(perPeriod - 1)).startsWith("INSERT INTO agg_weekly_toko");
        assertThat(sqls.get(2 * perPeriod - 1)).startsWith("INSERT INTO agg_monthly_toko");
    }

    /** 没有已结束的周期 → 不读任何库、不建临时表。 */
    @Test
    void noFinishedPeriodsDoesNothing() {
        LocalDate asOf = d("2026-11-26");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        TokoFamily f = new TokoFamily(reader);
        RollupContext ctx = ctx(jdbc, asOf, List.of());
        f.preparePeriods(d("2026-11-24"), asOf, asOf, ctx);
        f.computePeriods(d("2026-11-24"), asOf, asOf, ctx);
        verify(jdbc, never()).query(anyString(), any(RowCallbackHandler.class), any(), any());
        verify(jdbc, never()).execute(anyString());
        verify(reader, never()).paidOrderCount(any(), any());
    }

    private static ResultSet user(long uid, String first, String last) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(uid);
        when(rs.getTimestamp(2)).thenReturn(Timestamp.from(Instant.parse(first)));
        when(rs.getTimestamp(3)).thenReturn(Timestamp.from(Instant.parse(last)));
        return rs;
    }
}
