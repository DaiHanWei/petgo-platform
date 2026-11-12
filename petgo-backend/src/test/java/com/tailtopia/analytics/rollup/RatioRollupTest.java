package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import com.tailtopia.analytics.DauSource;
import com.tailtopia.analytics.rollup.RatioRollup.Derived;
import com.tailtopia.analytics.rollup.RatioRollup.Numerators;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * L0：指标族 ratio（analytics-pipeline Story 4.7 AC2 / AC4 / AC6 / AC7 / T4 / T7）——{@code ratio.sql} 可读、分段、
 * 不含当前日期函数、只用 Java 传入的参数；分母选择；比率 / 回访用户计算（分母 0 / NULL）；产品 #3 并集；
 * 准备步骤按 WIB 日界读业务库；注册与依赖顺序。全部 mock，无库。
 */
class RatioRollupTest {

    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-z_][a-z0-9_]*)");
    private static final Instant RUN_AT = Instant.parse("2026-11-21T20:00:00Z");
    private static final Set<String> ALLOWED = Set.of("stat_date", "dau_source", "dau", "toko_buyers", "s4_ratio",
            "nontoko_payers", "p3_ratio", "payers_ktp", "payers_tailsonality", "payers_passport", "payers_pet_slot",
            "payers_ai_unlock", "payers_vet_consult", "payers_gift", "payers_topup", "new_users", "returning_users",
            "computed_at");

    private static final Numerators N = new Numerators(5, 7, 1, 2, 3, 1, 1, 2, 4, 6, 3);

    private static RatioRollup family(DauSource source) {
        return new RatioRollup(mock(PayerCountReader.class), new AnalyticsDauSourceProperties(source.name()));
    }

    private static RollupContext ctx(JdbcTemplate jdbc) {
        return new RollupContext(LocalDate.parse("2026-11-20"), RUN_AT, List.of(), AnalyticsRollupProperties.defaults(),
                jdbc);
    }

    // ---------------------------------------------------------------- SQL 文件（AC6）

    @Test
    void sqlHasNoCurrentDateAndUsesOnlyParameters() {
        String sql = SqlRollupFamily.readScript(RatioRollup.NAME);
        String lower = sql.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("now()", "current_date", "current_timestamp", "localtimestamp",
                "clock_timestamp", "transaction_timestamp", "statement_timestamp");
        // 不跨库：埋点库 SQL 不碰业务表
        assertThat(lower).doesNotContain("shop_orders", "payment_intents", "keepsake_purchases", "users u");
        Map<String, List<String>> sections = RollupSqlSections.load(RatioRollup.NAME);
        assertThat(sections).containsOnlyKeys("denominator", "upsert");
        for (List<String> stmts : sections.values()) {
            assertThat(stmts).hasSize(1);
            Matcher m = PARAM.matcher(stmts.get(0));
            while (m.find()) {
                assertThat(ALLOWED).as("ratio 参数 :%s", m.group(1)).contains(m.group(1));
            }
            assertThat(stmts.get(0)).contains(":stat_date");
        }
    }

    /** AC2：GA4 取全版本日活（不是对账用的 V1.5.0+），SELF 取 agg_daily_activity 的 all 行。 */
    @Test
    void denominatorPicksSourceBySwitch() {
        String sql = family(DauSource.GA4).section("denominator").get(0);
        assertThat(sql).startsWith("SELECT CASE CAST(:dau_source AS varchar)")
                .contains("WHEN 'GA4' THEN (SELECT s.active_users_all", "FROM ga4_daily_snapshot s",
                        "WHEN 'SELF' THEN (SELECT a.dau_person", "FROM agg_daily_activity a",
                        "a.platform = 'all'")
                .doesNotContain("active_users_new_version", "dau_logged_in", "COALESCE");
    }

    /** upsert：每天一行、全部列覆盖（含 dau_source）；不写可识别个人的列。 */
    @Test
    void upsertOverwritesAllColumnsPerDay() {
        String sql = family(DauSource.GA4).section("upsert").get(0);
        assertThat(sql).startsWith("INSERT INTO agg_daily_ratio").contains("ON CONFLICT (stat_date) DO UPDATE SET");
        String columns = sql.substring(0, sql.indexOf("VALUES"));
        for (String c : ALLOWED) {
            if (!c.equals("stat_date")) {
                assertThat(sql).as(c).containsPattern("(?m)^    " + c + " += EXCLUDED\\." + c + ",?$");
            }
            assertThat(columns).as(c).contains(c);
        }
        assertThat(sql).doesNotContain("user_id", "anonymous_id", "person_key");
    }

    // ---------------------------------------------------------------- 计算（AC4 / AC7）

    @Test
    void denominatorNullMakesRatiosAndReturningNull() {
        Derived d = RatioRollup.derive(null, 5, 7, 3);
        assertThat(d.s4Ratio()).isNull();
        assertThat(d.p3Ratio()).isNull();
        assertThat(d.returningUsers()).isNull();
    }

    @Test
    void denominatorZeroMakesRatiosNullButKeepsReturning() {
        Derived d = RatioRollup.derive(0, 5, 7, 3);
        assertThat(d.s4Ratio()).isNull();
        assertThat(d.p3Ratio()).isNull();
        assertThat(d.returningUsers()).isEqualTo(-3);
        assertThat(RatioRollup.ratio(1, 0)).isNull();
        assertThat(RatioRollup.ratio(1, null)).isNull();
    }

    @Test
    void ratiosRoundToSixDecimalsAndReturningMayBeNegative() {
        Derived d = RatioRollup.derive(3, 1, 2, 5);
        assertThat(d.s4Ratio()).isEqualByComparingTo("0.333333");
        assertThat(d.p3Ratio()).isEqualByComparingTo("0.666667");
        assertThat(d.s4Ratio().scale()).isEqualTo(6);
        assertThat(d.returningUsers()).isEqualTo(-2);
        assertThat(RatioRollup.ratio(0, 10)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(RatioRollup.ratio(12, 1000)).isEqualByComparingTo("0.012");
    }

    /** 复审 R1：比值超出 numeric(9,6) 上限（分母极小）时封顶，不让 upsert 溢出卡死进度；分子大于分母但未超限照常。 */
    @Test
    void ratioCappedAtNumericNineSixMax() {
        assertThat(RatioRollup.ratio(2500, 2)).isEqualByComparingTo("999.999999");
        assertThat(RatioRollup.ratio(Integer.MAX_VALUE, 1)).isEqualByComparingTo(RatioRollup.RATIO_MAX);
        assertThat(RatioRollup.ratio(1999, 2)).isEqualByComparingTo("999.5");
        assertThat(RatioRollup.ratio(3, 2)).isEqualByComparingTo("1.5");
        Derived d = RatioRollup.derive(1, 5000, 1000, 0);
        assertThat(d.s4Ratio()).isEqualByComparingTo("999.999999");
        assertThat(d.p3Ratio()).isEqualByComparingTo("999.999999");
        assertThat(d.s4Ratio().precision() - d.s4Ratio().scale()).isLessThanOrEqualTo(3);
    }

    /** 产品 #3 分子 = 六类去重并集；送礼与充值只作参照、不进并集。 */
    @Test
    void nonTokoPayersIsUnionOfSixCategoriesOnly() {
        List<Supplier<Set<Long>>> six = List.of(() -> Set.of(1L, 2L), () -> Set.of(2L, 3L), () -> Set.of(),
                () -> Set.of(1L), () -> Set.of(4L), () -> Set.of(3L, 5L));
        Numerators n = RatioRollup.numerators(() -> Set.of(9L, 10L), six, () -> Set.of(6L, 7L, 8L),
                () -> Set.of(100L), 11);
        assertThat(n.tokoBuyers()).isEqualTo(2);
        assertThat(n.nontokoPayers()).isEqualTo(5);
        assertThat(new int[] {n.ktp(), n.tailsonality(), n.passport(), n.petSlot(), n.aiUnlock(), n.vetConsult()})
                .containsExactly(2, 2, 0, 1, 1, 2);
        assertThat(n.gift()).isEqualTo(3);
        assertThat(n.topup()).isEqualTo(1);
        assertThat(n.newUsers()).isEqualTo(11);
        assertThatThrownBy(() -> RatioRollup.numerators(Set::of, List.of(), Set::of, Set::of, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void upsertParamsCarryNumeratorsDerivedAndTypes() {
        MapSqlParameterSource p = RatioRollup.upsertParams(LocalDate.parse("2026-11-19"), DauSource.SELF, 20, N, RUN_AT);
        assertThat(p.getValue("stat_date")).isEqualTo(LocalDate.parse("2026-11-19"));
        assertThat(p.getValue("dau_source")).isEqualTo("SELF");
        assertThat(p.getValue("dau")).isEqualTo(20);
        assertThat((BigDecimal) p.getValue("s4_ratio")).isEqualByComparingTo("0.25");
        assertThat((BigDecimal) p.getValue("p3_ratio")).isEqualByComparingTo("0.35");
        assertThat(p.getValue("returning_users")).isEqualTo(17);
        assertThat(p.getValue("payers_gift")).isEqualTo(4);
        assertThat(p.getValue("payers_topup")).isEqualTo(6);
        assertThat(p.getValue("computed_at")).isEqualTo(OffsetDateTime.ofInstant(RUN_AT, ZoneOffset.UTC));
        assertThat(p.getSqlType("dau")).isEqualTo(Types.INTEGER);
        assertThat(p.getSqlType("s4_ratio")).isEqualTo(Types.NUMERIC);
        assertThat(p.getSqlType("returning_users")).isEqualTo(Types.INTEGER);
        assertThat(p.getParameterNames()).containsExactlyInAnyOrderElementsOf(ALLOWED);

        // AC7：分母缺失 → dau / 比率 / 回访为 NULL，分子照写
        MapSqlParameterSource missing = RatioRollup.upsertParams(LocalDate.parse("2026-11-19"), DauSource.GA4, null, N,
                RUN_AT);
        assertThat(missing.getValue("dau")).isNull();
        assertThat(missing.getValue("s4_ratio")).isNull();
        assertThat(missing.getValue("p3_ratio")).isNull();
        assertThat(missing.getValue("returning_users")).isNull();
        assertThat(missing.getValue("toko_buyers")).isEqualTo(5);
        assertThat(missing.getValue("nontoko_payers")).isEqualTo(7);
        assertThat(missing.getValue("new_users")).isEqualTo(3);
        assertThat(missing.getValue("dau_source")).isEqualTo("GA4");
    }

    // ---------------------------------------------------------------- 准备 / 计算流程

    /** prepare 按 WIB 日界 [当天 0 点, 次日 0 点) 读业务库（UTC = 前一天 17:00）。 */
    @Test
    void prepareReadsBusinessByWibDayBoundaries() {
        PayerCountReader reader = mock(PayerCountReader.class);
        Instant from = Instant.parse("2026-11-18T17:00:00Z");
        Instant to = Instant.parse("2026-11-19T17:00:00Z");
        when(reader.tokoBuyers(from, to)).thenReturn(Set.of(1L));
        when(reader.ktpPayers(from, to)).thenReturn(Set.of(2L));
        when(reader.tailsonalityPayers(from, to)).thenReturn(Set.of());
        when(reader.passportPayers(from, to)).thenReturn(Set.of());
        when(reader.petSlotPayers(from, to)).thenReturn(Set.of());
        when(reader.aiUnlockPayers(from, to)).thenReturn(Set.of(2L));
        when(reader.vetConsultPayers(from, to)).thenReturn(Set.of());
        when(reader.giftSenders(from, to)).thenReturn(Set.of());
        when(reader.topupPayers(from, to)).thenReturn(Set.of());
        when(reader.newUsers(from, to)).thenReturn(4L);
        RatioRollup f = new RatioRollup(reader, new AnalyticsDauSourceProperties("GA4"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class))).thenReturn(List.of(10L));

        LocalDate day = LocalDate.parse("2026-11-19");
        f.prepareDay(day, day, ctx(jdbc));
        verify(reader).tokoBuyers(from, to);
        verify(reader).newUsers(from, to);
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));

        f.computeDay(day, day, ctx(jdbc));
        verify(jdbc, times(1)).query(any(PreparedStatementCreator.class), any(RowMapper.class));
        verify(jdbc, times(1)).update(any(PreparedStatementCreator.class));
        // prepare 结果只用一次
        assertThatThrownBy(() -> f.computeDay(day, day, ctx(jdbc))).isInstanceOf(IllegalStateException.class);
    }

    /** AC7：分母查不到（单行 NULL）不报错，照常 upsert。 */
    @Test
    void computeWithMissingDenominatorStillWrites() {
        PayerCountReader reader = mock(PayerCountReader.class);
        when(reader.tokoBuyers(any(), any())).thenReturn(Set.of());
        when(reader.ktpPayers(any(), any())).thenReturn(Set.of());
        when(reader.tailsonalityPayers(any(), any())).thenReturn(Set.of());
        when(reader.passportPayers(any(), any())).thenReturn(Set.of());
        when(reader.petSlotPayers(any(), any())).thenReturn(Set.of());
        when(reader.aiUnlockPayers(any(), any())).thenReturn(Set.of());
        when(reader.vetConsultPayers(any(), any())).thenReturn(Set.of());
        when(reader.giftSenders(any(), any())).thenReturn(Set.of());
        when(reader.topupPayers(any(), any())).thenReturn(Set.of());
        RatioRollup f = new RatioRollup(reader, new AnalyticsDauSourceProperties("GA4"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenReturn(Arrays.asList((Object) null));
        LocalDate day = LocalDate.parse("2026-11-19");
        f.prepareDay(day, day, ctx(jdbc));
        f.computeDay(day, day, ctx(jdbc));
        verify(jdbc, times(1)).update(any(PreparedStatementCreator.class));
    }

    @Test
    void computeWithoutPrepareFails() {
        RatioRollup f = family(DauSource.GA4);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LocalDate day = LocalDate.parse("2026-11-19");
        assertThatThrownBy(() -> f.computeDay(day, day, ctx(jdbc))).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(any(PreparedStatementCreator.class));
    }

    // ---------------------------------------------------------------- 注册与顺序

    @Test
    void registeredAfterActivityWithThreeDayRecompute() {
        RollupFamily f = new RollupConfig().ratioRollupFamily(mock(PayerCountReader.class),
                new AnalyticsDauSourceProperties("SELF"));
        assertThat(f.name()).isEqualTo("ratio");
        assertThat(f.recomputeDays()).isEqualTo(3);
        assertThat(f.dependsOn()).containsExactly("activity");
        assertThat(f.hasPeriods()).isFalse();
        List<RollupFamily> ordered = RollupService.orderByDependencies(
                List.of(f, new SqlRollupFamily("activity", 3, Set.of())));
        assertThat(ordered).extracting(RollupFamily::name).containsExactly("activity", "ratio");
    }
}
