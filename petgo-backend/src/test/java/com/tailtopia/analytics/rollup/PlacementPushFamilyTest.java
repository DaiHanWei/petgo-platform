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

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.rollup.BusinessPlacementReader.Campaign;
import com.tailtopia.analytics.rollup.BusinessPlacementReader.PaidOrder;
import com.tailtopia.analytics.rollup.PlacementFamily.ScanRow;
import com.tailtopia.analytics.rollup.PlacementTokoRule.Target;
import com.tailtopia.analytics.rollup.RollupPeriods.Kind;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlProvider;

/**
 * L0：指标族 placement / push（analytics-pipeline Story 4.4 T4 / T5 / T8）——SQL 文件可读、分段、不含当前日期函数、
 * 只用 Java 传入的参数；事件映射与点击判定；跨库匹配的准备步骤（事务外）与临时表；周 / 月选择；推送配置宽松解析。全部 mock，无库。
 */
class PlacementPushFamilyTest {

    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-z_][a-z0-9_]*)");
    private static final Instant RUN_AT = Instant.parse("2026-11-21T20:00:00Z");

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static RollupContext ctx(JdbcTemplate jdbc, LocalDate asOf, List<Long> excluded) {
        return new RollupContext(asOf, RUN_AT, excluded, AnalyticsRollupProperties.defaults(), jdbc);
    }

    // ---------------------------------------------------------------- SQL 文件

    @ParameterizedTest
    @ValueSource(strings = {"placement", "push"})
    void sqlReadableSectionedAndNoCurrentDate(String family) {
        String sql = SqlRollupFamily.readScript(family);
        String lower = sql.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("now()", "current_date", "current_timestamp", "localtimestamp",
                "clock_timestamp", "transaction_timestamp", "statement_timestamp");
        assertThat(lower).doesNotContain("date(event_time", "event_time::date", "received_at");
        assertThat(lower).contains("v_events_person", "tmp_excluded_users", "event_date", "is_production",
                "not p.is_suspicious", "x.user_id is null");
        Map<String, List<String>> sections = RollupSqlSections.load(family);
        assertThat(sections).containsOnlyKeys("compute", "daily", "weekly", "monthly");
        Set<String> allowed = family.equals("push")
                ? Set.of("period_start", "period_end", "as_of", "computed_at", "campaign_days")
                : Set.of("period_start", "period_end", "as_of", "computed_at");
        for (List<String> stmts : sections.values()) {
            for (String stmt : stmts) {
                Matcher m = PARAM.matcher(stmt);
                while (m.find()) {
                    assertThat(allowed).as("%s 参数 :%s", family, m.group(1)).contains(m.group(1));
                }
            }
        }
    }

    /** 日 / 周 / 月表先删后插；写入列不含可识别个人的字段；周 / 月 is_complete = 周期末 ≤ as_of。 */
    @ParameterizedTest
    @ValueSource(strings = {"placement", "push"})
    void deleteThenInsertPerPeriod(String family) {
        Map<String, List<String>> s = RollupSqlSections.load(family);
        assertThat(s.get("daily")).hasSize(2);
        assertThat(s.get("daily").get(0)).isEqualTo(
                "DELETE FROM agg_daily_" + family + " WHERE stat_date = CAST(:period_start AS date)");
        assertThat(s.get("daily").get(1)).startsWith("INSERT INTO agg_daily_" + family);
        for (String kind : new String[] {"weekly", "monthly"}) {
            List<String> stmts = s.get(kind);
            assertThat(stmts).hasSize(2);
            assertThat(stmts.get(0)).isEqualTo(
                    "DELETE FROM agg_" + kind + "_" + family + " WHERE period_start = CAST(:period_start AS date)");
            assertThat(stmts.get(1)).startsWith("INSERT INTO agg_" + kind + "_" + family)
                    .contains("CAST(:period_end AS date) <= CAST(:as_of AS date)");
        }
        for (String kind : new String[] {"daily", "weekly", "monthly"}) {
            String insert = s.get(kind).get(1);
            String cols = insert.substring(0, insert.indexOf(')') + 1);
            assertThat(cols).doesNotContain("user_id", "anonymous_id", "person_key", "order_token");
        }
        // compute：只读到 LEAST(period_end, as_of)；临时表 ON COMMIT DROP
        String compute = String.join("\n", s.get("compute"));
        assertThat(compute).contains("LEAST(CAST(:period_end AS date), CAST(:as_of AS date))", "ON COMMIT DROP");
    }

    @Test
    void placementEventMappingClickRuleAndOrderWindow() {
        String compute = String.join("\n", RollupSqlSections.load("placement").get("compute"));
        for (String e : PlacementFamily.IMPRESSION_EVENTS) {
            assertThat(compute).contains("'" + e + "'");
        }
        for (String e : PlacementFamily.CLICK_EVENTS) {
            assertThat(compute).contains("'" + e + "'");
        }
        assertThat(compute).doesNotContain("ops_popup_closed");
        assertThat(compute).contains("p.properties ->> 'action' = 'register'",
                "p.properties ->> 'placement_type' = 'ops_popup'", "p.properties ->> 'placement_type' = 'toko_banner'",
                "p.properties ->> 'placement_type' = 'newbie_coupon'",
                "length(p.properties ->> 'placement_token') BETWEEN 1 AND 64", "p.source = 'APP'");
        // 带单：点击时间 < paid_at ≤ 点击时间 + 7 天，按订单 token 去重；按归属后的用户匹配
        assertThat(compute).contains("o.user_id = c.person_user_id", "o.paid_at > c.event_time",
                "o.paid_at <= c.event_time + interval '7 days'", "count(DISTINCT o.order_token)",
                "count(DISTINCT e.person_key) FILTER (WHERE e.kind = 'C')", "tmp_placement_config", "tmp_paid_orders");
        String daily = RollupSqlSections.load("placement").get("daily").get(1);
        assertThat(daily).contains("CAST(:period_end AS date) + 7 <= CAST(:as_of AS date)");
    }

    @Test
    void pushOpenedCouponSentAndCampaignCumulative() {
        Map<String, List<String>> s = RollupSqlSections.load("push");
        String compute = String.join("\n", s.get("compute"));
        assertThat(compute).contains("p.event = 'push_opened'", "'unknown'", "left(p.properties ->> 'push_type', 40)",
                "o.push_type = 'OPS_ANNOUNCEMENT' AND length(o.raw_token) BETWEEN 1 AND 32",
                "p.event = 'toko_coupon_expiry_push_sent'", "p.source = 'SERVER'", "SELECT 'COUPON_EXPIRING', ''",
                "count(DISTINCT o.person_key)");
        String daily = s.get("daily").get(1);
        assertThat(daily).contains("tmp_campaigns", "c.sent_date + (CAST(:campaign_days AS int) - 1)",
                "LEAST(CAST(:period_start AS date),", "CAST(:campaign_days AS int) > 0", "c.planned_count",
                "c.recipient_count", "c.promotes_toko");
    }

    @Test
    void sectionParserRejectsBadScripts() {
        assertThatThrownBy(() -> RollupSqlSections.parse("SELECT 1;", "x")).hasMessageContaining("没有任何 @section");
        assertThatThrownBy(() -> RollupSqlSections.parse("SELECT 1;\n-- @section a\nSELECT 2;", "x"))
                .hasMessageContaining("之前不能有语句");
        assertThatThrownBy(() -> RollupSqlSections.parse("-- @section a\n-- 只有注释\n-- @section b\nSELECT 2;", "x"))
                .hasMessageContaining("a 没有语句");
        assertThatThrownBy(() -> RollupSqlSections.parse("-- @section a\nSELECT 1;\n-- @section a\nSELECT 2;", "x"))
                .hasMessageContaining("重复");
        Map<String, List<String>> ok = RollupSqlSections.parse("-- 头\n-- @section a\nSELECT 1;\nSELECT 2;\n"
                + "-- @section b\nSELECT ';';", "x");
        assertThat(ok.get("a")).containsExactly("SELECT 1", "SELECT 2");
        assertThat(ok.get("b")).containsExactly("SELECT ';'");
        assertThatThrownBy(() -> RollupSqlSections.require(ok, "c", "x")).hasMessageContaining("缺 @section c");
    }

    // ---------------------------------------------------------------- 注册

    @Test
    void registration() {
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        RollupConfig cfg = new RollupConfig();
        RollupFamily placement = cfg.placementRollupFamily(reader);
        assertThat(placement.name()).isEqualTo("placement");
        assertThat(placement.recomputeDays()).isEqualTo(10);
        assertThat(placement.hasPeriods()).isTrue();
        assertThat(placement.dependsOn()).isEmpty();
        // 7 天带单 + 3 天晚到：最早回到 asOf − 9
        assertThat(RollupService.startDate(null, d("2026-11-20"), placement.recomputeDays())).isEqualTo(d("2026-11-11"));

        PushFamily push = cfg.pushRollupFamily(reader, PushRollupProperties.defaults(), mock(OpsAlertService.class),
                "test");
        assertThat(push.name()).isEqualTo("push");
        assertThat(push.recomputeDays()).isEqualTo(3);
        assertThat(push.hasPeriods()).isTrue();
    }

    // ---------------------------------------------------------------- placement：纯计算

    @Test
    void validScanRows() {
        assertThat(PlacementFamily.valid(new ScanRow("ops_popup_shown", "ops_popup", "tok", 1))).isTrue();
        assertThat(PlacementFamily.valid(new ScanRow("toko_banner_tapped", "toko_banner", "b1", 1))).isTrue();
        assertThat(PlacementFamily.valid(new ScanRow("toko_newbie_coupon_popup_tapped", "newbie_coupon", "c", 1)))
                .isTrue();
        // 缺 token、空串、超 64、类型与事件名不一致
        assertThat(PlacementFamily.valid(new ScanRow("ops_popup_shown", "ops_popup", null, 1))).isFalse();
        assertThat(PlacementFamily.valid(new ScanRow("ops_popup_shown", "ops_popup", "", 1))).isFalse();
        assertThat(PlacementFamily.valid(new ScanRow("ops_popup_shown", "ops_popup", "x".repeat(65), 1))).isFalse();
        assertThat(PlacementFamily.valid(new ScanRow("ops_popup_shown", "toko_banner", "t", 1))).isFalse();
        assertThat(PlacementFamily.valid(new ScanRow("toko_banner_shown", null, "t", 1))).isFalse();
    }

    /** 订单查询区间 = [点击日 0 点 WIB, 点击日 + 8 天 0 点 WIB)，点击时间越出当日时向外扩。 */
    @Test
    void orderQueryRange() {
        LocalDate day = d("2026-11-10");
        // 2026-11-10 00:00 WIB = 2026-11-09T17:00Z；+8 天 = 2026-11-17T17:00Z
        assertThat(PlacementFamily.orderFrom(day, null)).isEqualTo(Instant.parse("2026-11-09T17:00:00Z"));
        assertThat(PlacementFamily.orderTo(day, null)).isEqualTo(Instant.parse("2026-11-17T17:00:00Z"));
        Instant late = Instant.parse("2026-11-10T16:59:59Z"); // 23:59:59 WIB
        assertThat(PlacementFamily.orderFrom(day, late)).isEqualTo(Instant.parse("2026-11-09T17:00:00Z"));
        assertThat(PlacementFamily.orderTo(day, late)).isEqualTo(Instant.parse("2026-11-17T17:00:00Z"));
        Instant early = Instant.parse("2026-11-09T10:00:00Z");
        Instant after = Instant.parse("2026-11-11T10:00:00Z");
        assertThat(PlacementFamily.orderFrom(day, early)).isEqualTo(early);
        assertThat(PlacementFamily.orderTo(day, after)).isEqualTo(after.plusSeconds(7 * 86400).plusNanos(1000));
    }

    /** 与重算窗口相交的周与月；首日早于汇总下限的跳过。 */
    @Test
    void computablePeriods() {
        List<Period> ps = PlacementFamily.computablePeriods(d("2026-11-25"), d("2026-12-02"), d("2026-12-02"), 92);
        assertThat(ps).containsExactly(
                new Period(Kind.WEEK, d("2026-11-23"), d("2026-11-29")),
                new Period(Kind.WEEK, d("2026-11-30"), d("2026-12-06")),
                new Period(Kind.MONTH, d("2026-11-01"), d("2026-11-30")),
                new Period(Kind.MONTH, d("2026-12-01"), d("2026-12-31")));
        // 下限 08-21：8 月与 08-17 那周跳过
        LocalDate asOf = d("2026-11-20");
        assertThat(RollupService.floor(asOf, 92)).isEqualTo(d("2026-08-21"));
        assertThat(PlacementFamily.computablePeriods(d("2026-08-21"), d("2026-08-30"), asOf, 92)).containsExactly(
                new Period(Kind.WEEK, d("2026-08-24"), d("2026-08-30")));
    }

    // ---------------------------------------------------------------- placement：准备（事务外）→ 计算（事务内）

    @SuppressWarnings("unchecked")
    @Test
    void prepareDayReadsBusinessOutsideAndComputeFillsTemps() throws Exception {
        LocalDate day = d("2026-11-10");
        LocalDate asOf = d("2026-11-20");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(jdbc.query(eq(PlacementFamily.SCAN_PLACEMENTS_SQL), any(RowMapper.class), eq(day), eq(day)))
                .thenReturn(List.of(new ScanRow("ops_popup_shown", "ops_popup", "p1", 3),
                        new ScanRow("ops_popup_tapped", "ops_popup", "p1", 2),
                        new ScanRow("toko_banner_shown", "toko_banner", "gone", 1),
                        new ScanRow("toko_newbie_coupon_popup_shown", "newbie_coupon", "n1", 1),
                        new ScanRow("ops_popup_shown", "ops_popup", null, 4)));
        // 点击过的人：用户 1（正常）、用户 7（排除名单）
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            h.processRow(clicker(1L, "2026-11-10T03:00:00Z", "2026-11-10T05:00:00Z"));
            h.processRow(clicker(7L, "2026-11-10T04:00:00Z", "2026-11-10T04:00:00Z"));
            return null;
        }).when(jdbc).query(eq(PlacementFamily.SCAN_CLICKERS_SQL), any(RowCallbackHandler.class), eq(day), eq(day));
        when(reader.opsPopupTargets()).thenReturn(Map.of("p1", new Target("PRODUCT", "x")));
        when(reader.shopBannerTargets()).thenReturn(Map.of());
        when(reader.paidOrders(any(), any(), any())).thenReturn(
                List.of(new PaidOrder(1L, "o1", Instant.parse("2026-11-12T00:00:00Z"))));

        PlacementFamily f = new PlacementFamily(reader);
        RollupContext ctx = ctx(jdbc, asOf, List.of(7L));
        // 没有 prepare 不能算
        assertThatThrownBy(() -> f.computeDay(day, asOf, ctx)).isInstanceOf(IllegalStateException.class);

        f.prepareDay(day, asOf, ctx);
        ArgumentCaptor<Collection<Long>> users = ArgumentCaptor.forClass(Collection.class);
        verify(reader).paidOrders(users.capture(), eq(Instant.parse("2026-11-09T17:00:00Z")),
                eq(Instant.parse("2026-11-17T17:00:00Z")));
        assertThat(users.getValue()).containsExactly(1L);
        verify(jdbc, never()).execute(anyString());

        f.computeDay(day, asOf, ctx);
        verify(jdbc).execute(PlacementFamily.CREATE_CONFIG_SQL);
        verify(jdbc).update(eq(PlacementFamily.FILL_CONFIG_SQL), any(PreparedStatementSetter.class));
        verify(jdbc).execute(PlacementFamily.CREATE_ORDERS_SQL);
        verify(jdbc).update(eq(PlacementFamily.FILL_ORDERS_SQL), any(PreparedStatementSetter.class));
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc, times(f.section("compute").size() + 2)).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(sqls.size() - 2)).startsWith("DELETE FROM agg_daily_placement");
        assertThat(sqls.get(sqls.size() - 1)).startsWith("INSERT INTO agg_daily_placement");
        // prepare 只供一次
        assertThatThrownBy(() -> f.computeDay(day, asOf, ctx)).isInstanceOf(IllegalStateException.class);
    }

    /** 没有点击过的登录用户 → 不查订单；没有运营位 → 不读配置。 */
    @SuppressWarnings("unchecked")
    @Test
    void prepareSkipsBusinessQueriesWhenNothingToMatch() {
        LocalDate day = d("2026-11-10");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(jdbc.query(eq(PlacementFamily.SCAN_PLACEMENTS_SQL), any(RowMapper.class), eq(day), eq(day)))
                .thenReturn(List.of());
        PlacementFamily f = new PlacementFamily(reader);
        RollupContext ctx = ctx(jdbc, d("2026-11-20"), List.of());
        f.prepareDay(day, d("2026-11-20"), ctx);
        verify(reader, never()).paidOrders(any(), any(), any());
        verify(reader, never()).opsPopupTargets();
        verify(reader, never()).shopBannerTargets();
        f.computeDay(day, d("2026-11-20"), ctx);
        verify(jdbc, never()).update(eq(PlacementFamily.FILL_CONFIG_SQL), any(PreparedStatementSetter.class));
        verify(jdbc, never()).update(eq(PlacementFamily.FILL_ORDERS_SQL), any(PreparedStatementSetter.class));
    }

    /** 周期单元：一次准备（覆盖所有周期的区间），逐周期 compute + weekly / monthly。 */
    @SuppressWarnings("unchecked")
    @Test
    void periodsPrepareOnceAndRunEachPeriod() {
        LocalDate asOf = d("2026-12-02");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(jdbc.query(eq(PlacementFamily.SCAN_PLACEMENTS_SQL), any(RowMapper.class), any(), any()))
                .thenReturn(List.of());
        PlacementFamily f = new PlacementFamily(reader);
        RollupContext ctx = ctx(jdbc, asOf, List.of());
        f.preparePeriods(d("2026-11-25"), asOf, asOf, ctx);
        // 区间 = 最早周期首日 11-01 到 min(最晚周期末日, asOf)
        verify(jdbc).query(eq(PlacementFamily.SCAN_PLACEMENTS_SQL), any(RowMapper.class), eq(d("2026-11-01")),
                eq(asOf));
        f.computePeriods(d("2026-11-25"), asOf, asOf, ctx);
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        int perPeriod = f.section("compute").size() + 2;
        verify(jdbc, times(4 * perPeriod)).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(perPeriod - 1)).startsWith("INSERT INTO agg_weekly_placement");
        assertThat(sqls.get(4 * perPeriod - 1)).startsWith("INSERT INTO agg_monthly_placement");
    }

    private static ResultSet clicker(long uid, String first, String last) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(uid);
        when(rs.getTimestamp(2)).thenReturn(Timestamp.from(Instant.parse(first)));
        when(rs.getTimestamp(3)).thenReturn(Timestamp.from(Instant.parse(last)));
        return rs;
    }

    // ---------------------------------------------------------------- push

    @Test
    void pushPropertiesLenient() {
        assertThat(PushRollupProperties.defaults().pushCampaignDays()).isEqualTo(14);
        assertThat(PushRollupProperties.defaults().invalidReason()).isNull();
        assertThat(new PushRollupProperties(" 30 ").pushCampaignDays()).isEqualTo(30);
        for (String bad : new String[] {"14d", "", "0", "93", "-1", null}) {
            PushRollupProperties p = new PushRollupProperties(bad);
            assertThat(p.pushCampaignDays()).as("值 %s", bad).isZero();
            assertThat(p.invalidReason()).contains("analytics.rollup.push-campaign-days");
            if (bad != null && !bad.isEmpty()) {
                assertThat(p.invalidReason()).doesNotContain(bad);
            }
        }
    }

    @Test
    void pushConfigAlertOnlyWhenInvalid() {
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        OpsAlertService alerts = mock(OpsAlertService.class);
        new PushFamily(reader, PushRollupProperties.defaults(), alerts, "test").checkConfigOnStartup();
        verify(alerts, never()).send(any(), anyString(), anyString());
        new PushFamily(reader, new PushRollupProperties("abc"), alerts, "test").checkConfigOnStartup();
        verify(alerts).send(eq(OpsChannel.TECH), eq(PushFamily.CONFIG_ALERT_KEY), anyString());
        // 告警失败不抛
        OpsAlertService broken = mock(OpsAlertService.class);
        doAnswer(inv -> {
            throw new IllegalStateException("x");
        }).when(broken).send(any(), anyString(), anyString());
        new PushFamily(reader, new PushRollupProperties("abc"), broken, "test").checkConfigOnStartup();
    }

    /** 活动发送日按 WIB 换算；跳转规则同弹窗；发送时间为空的跳过。 */
    @Test
    void campaignRows() {
        List<PushFamily.CampaignRow> rows = PushFamily.toRows(List.of(
                new Campaign("c1", 100, 98, "APP_PAGE", "TOKO", Instant.parse("2026-11-10T17:30:00Z")),
                new Campaign("c2", null, 5, "EXTERNAL", "https://x", Instant.parse("2026-11-10T16:59:00Z")),
                new Campaign("c3", 1, 1, "NONE", null, null)));
        assertThat(rows).containsExactly(
                new PushFamily.CampaignRow("c1", d("2026-11-11"), 100, 98, true),
                new PushFamily.CampaignRow("c2", d("2026-11-10"), null, 5, false));
    }

    /** 一次运行只读一次活动表；computeDay 必须紧跟同一日的 prepareDay；campaign_days 传入 SQL。 */
    @Test
    void pushReadsCampaignsOncePerRun() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        when(reader.campaigns()).thenReturn(List.of(
                new Campaign("c1", 10, 9, "PRODUCT", "p", Instant.parse("2026-11-10T03:00:00Z"))));
        PushFamily f = new PushFamily(reader, PushRollupProperties.defaults(), null, "test");
        LocalDate asOf = d("2026-11-20");
        RollupContext ctx = ctx(jdbc, asOf, List.of());
        assertThatThrownBy(() -> f.computeDay(asOf, asOf, ctx)).isInstanceOf(IllegalStateException.class);
        f.prepareDay(asOf.minusDays(1), asOf, ctx);
        f.computeDay(asOf.minusDays(1), asOf, ctx);
        f.prepareDay(asOf, asOf, ctx);
        f.computeDay(asOf, asOf, ctx);
        verify(reader, times(1)).campaigns();
        verify(jdbc, times(2)).execute(PushFamily.CREATE_CAMPAIGNS_SQL);
        verify(jdbc, times(2)).update(eq(PushFamily.FILL_CAMPAIGNS_SQL), any(PreparedStatementSetter.class));
        // 新的一次运行 → 重读
        f.prepareDay(asOf, asOf, ctx(jdbc, asOf, List.of()));
        verify(reader, times(2)).campaigns();
    }

    @Test
    void pushPeriodsRunWithoutBusinessData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BusinessPlacementReader reader = mock(BusinessPlacementReader.class);
        PushFamily f = new PushFamily(reader, PushRollupProperties.defaults(), null, "test");
        LocalDate asOf = d("2026-11-20");
        f.computePeriods(d("2026-11-18"), asOf, asOf, ctx(jdbc, asOf, List.of()));
        verify(reader, never()).campaigns();
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);
        int perPeriod = f.section("compute").size() + 2;
        // 一周 + 一个月
        verify(jdbc, times(2 * perPeriod)).update(psc.capture());
        List<String> sqls = psc.getAllValues().stream().map(p -> ((SqlProvider) p).getSql()).toList();
        assertThat(sqls.get(perPeriod - 1)).startsWith("INSERT INTO agg_weekly_push");
        assertThat(sqls.get(2 * perPeriod - 1)).startsWith("INSERT INTO agg_monthly_push");
    }
}
