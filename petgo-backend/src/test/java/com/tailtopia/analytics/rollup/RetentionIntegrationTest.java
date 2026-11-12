package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.rollup.RollupPeriods.Period;
import com.tailtopia.analytics.rollup.RollupService.Outcome;
import com.tailtopia.analytics.store.DetailPartitions;
import com.tailtopia.analytics.store.EventTimeCorrector;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：留存（{@code retention}）与安装后建档（{@code install_profile}）指标族（analytics-pipeline Story 4.3 AC1–AC4），
 * 真业务库 + 埋点测试库 {@code petgo_analytics_test}（前置同 {@code RollupActivityInstallIntegrationTest}）。
 *
 * <ul>
 *   <li>隔离：{@code agg_cohort_retention} / {@code agg_monthly_install_profile} 没有平台列，共享库里别的测试的事件也会算进去。
 *       所以每个断言都按「先在同一 {@code as_of} 跑一次取基线 → 造数 → 再跑一次」取<b>增量</b>；成熟度（列是否为空）只取决于日期，
 *       直接断言。与 {@code agg_daily_install} 的相等断言两边读同一份数据，直接比绝对值。</li>
 *   <li>组日期取 WIB 今天往前 30–45 天（避开别的测试常用的最近几天），月份取两个月前的自然月；分区按需建。</li>
 *   <li>明细 / 关联行按 {@code message_id} 前缀清理。{@code event_date} 由测试直接给成 WIB 日。</li>
 * </ul>
 */
class RetentionIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;
    @Autowired
    private JdbcTemplate businessJdbc;

    private RollupService service;
    private String prefix;
    private String platform;
    private LocalDate todayWib;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        OpsAlertService alerts = mock(OpsAlertService.class);
        service = new RollupService(state, analyticsJdbc, analyticsTx, businessJdbc,
                new RollupProgressRepository(analyticsJdbc),
                List.of(new SqlRollupFamily("install"), new SqlRollupFamily("retention", 16, java.util.Set.of()),
                        new InstallProfileFamily()),
                AnalyticsRollupProperties.defaults(), alerts, "test", Clock.systemUTC());
        long n = SEQ.incrementAndGet();
        prefix = "it-ret-" + n + "-";
        platform = "it" + Long.toString(n % 1_000_000_000_000L, 36);
        todayWib = EventTimeCorrector.wibDate(Instant.now());
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM identity_links WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM agg_daily_install WHERE platform = ?", platform);
    }

    // ---------------------------------------------------------------- 造数

    private void partitions(LocalDate from, LocalDate to) {
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + DetailPartitions.partitionName(d)
                        + " PARTITION OF " + DetailPartitions.PARENT + " FOR VALUES FROM ('" + d + "') TO ('"
                        + d.plusDays(1) + "')");
            }
        });
    }

    private static Instant wib(LocalDate d, int hour) {
        return d.atTime(hour, 0).atZone(EventTimeCorrector.WIB).toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static String anon() {
        return UUID.randomUUID().toString();
    }

    private void event(String event, String anon, Long userId, LocalDate day, int hour, boolean production,
            boolean suspicious) {
        Instant at = wib(day, hour);
        analyticsJdbc.update("""
                INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, is_suspicious, source,
                                           anonymous_id, user_id, user_key, platform, is_production, properties)
                VALUES (?, ?, ?, ?, ?, ?, 'APP', ?, ?, ?, ?, ?, '{}'::jsonb)""",
                prefix + "det-" + SEQ.incrementAndGet(), event, utc(at), utc(at), day, suspicious, anon, userId,
                userId == null ? null : AnalyticsDistinctId.of(userId), platform, production);
    }

    /** 生产、非可疑、APP。 */
    private void event(String event, String anon, Long userId, LocalDate day, int hour) {
        event(event, anon, userId, day, hour, true, false);
    }

    private void link(String anon, long userId, LocalDate day, int hour) {
        analyticsJdbc.update("INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id) "
                + "VALUES (?, ?, ?, ?, ?)", anon, userId, AnalyticsDistinctId.of(userId), utc(wib(day, hour)),
                prefix + "link-" + SEQ.incrementAndGet());
    }

    private long vetUser() {
        long id = newUser().getId();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", id);
        return id;
    }

    // ---------------------------------------------------------------- 读结果

    /** {cohort_size, d1, d3, d7, w1}；没有行 → null。 */
    private Long[] cohort(String type, LocalDate date) {
        List<Long[]> rows = analyticsJdbc.query("SELECT cohort_size, d1_retained, d3_retained, d7_retained, w1_retained "
                + "FROM agg_cohort_retention WHERE cohort_type = ? AND cohort_date = ?", (rs, i) -> new Long[] {
                    rs.getObject(1, Long.class), rs.getObject(2, Long.class), rs.getObject(3, Long.class),
                    rs.getObject(4, Long.class), rs.getObject(5, Long.class)}, type, date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 跑 retention 一个组日期，返回该行。 */
    private Long[] runRetention(String type, LocalDate date, LocalDate asOf) {
        assertThat(service.rerun(date, date, List.of("retention"), asOf).outcome()).isEqualTo(Outcome.DONE);
        Long[] row = cohort(type, date);
        assertThat(row).as("组为空也写行").isNotNull();
        return row;
    }

    /** 逐列增量；任一边为空 → 空（成熟度相同时两边同空）。 */
    private static Long[] delta(Long[] after, Long[] before) {
        Long[] out = new Long[after.length];
        for (int i = 0; i < after.length; i++) {
            assertThat(after[i] == null).as("列 %s 成熟度不随数据变化", i).isEqualTo(before[i] == null);
            out[i] = after[i] == null ? null : after[i] - before[i];
        }
        return out;
    }

    private Map<String, Object> month(LocalDate monthStart) {
        return analyticsJdbc.queryForMap("SELECT new_installs, with_pet_profile, is_complete "
                + "FROM agg_monthly_install_profile WHERE stat_month = ?", monthStart);
    }

    private static long n(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }

    // ---------------------------------------------------------------- AC1：安装组 D1 / D3 / D7

    @Test
    void installCohortMaturityAndAttribution() {
        LocalDate x = todayWib.minusDays(45);
        partitions(x, x.plusDays(8));
        LocalDate asOf3 = x.plusDays(3);
        LocalDate asOf7 = x.plusDays(7);
        Long[] base3 = runRetention("INSTALL", x, asOf3);
        Long[] base7 = runRetention("INSTALL", x, asOf7);

        // P1 X+1 打开（另有安装当天的打开）；P2 X+3 打开；P3 X+7 打开；P4 只在安装当天打开（不算 D1）
        String p1 = anon();
        event("Application Installed", p1, null, x, 9);
        event("Application Opened", p1, null, x, 10);
        event("Application Opened", p1, null, x.plusDays(1), 10);
        String p2 = anon();
        event("Application Installed", p2, null, x, 9);
        event("Application Opened", p2, null, x.plusDays(3), 10);
        String p3 = anon();
        event("Application Installed", p3, null, x, 9);
        event("Application Opened", p3, null, x.plusDays(7), 10);
        String p4 = anon();
        event("Application Installed", p4, null, x, 9);
        event("Application Opened", p4, null, x, 11);
        // P5：安装（未登录，匿名 A）→ 当晚注册登录（A→U1）→ X+1 以 U1 打开 = 同一人，算 D1
        long u1 = newUser().getId();
        String a = anon();
        event("Application Installed", a, null, x, 8);
        link(a, u1, x, 20);
        event("signup_succeeded", a, u1, x, 20);
        event("Application Opened", a, u1, x.plusDays(1), 9);
        // 排除：非生产、可疑、匿名安装经关联归到兽医
        String np = anon();
        event("Application Installed", np, null, x, 9, false, false);
        event("Application Opened", np, null, x.plusDays(1), 9, false, false);
        String sus = anon();
        event("Application Installed", sus, null, x, 9, true, true);
        event("Application Opened", sus, null, x.plusDays(1), 9);
        long vet = vetUser();
        String v = anon();
        event("Application Installed", v, null, x, 7);
        link(v, vet, x, 8);
        event("Application Opened", v, vet, x.plusDays(1), 9);
        // 升级不算新装
        String upd = anon();
        event("Application Updated", upd, null, x, 9);
        event("Application Opened", upd, null, x.plusDays(1), 9);

        // as_of = X+3：D7 未满为空，D1 / D3 有值
        Long[] r3 = runRetention("INSTALL", x, asOf3);
        assertThat(r3[3]).as("d7 未满 7 天").isNull();
        assertThat(r3[4]).as("w1 只用于 ONBOARDING").isNull();
        assertThat(delta(r3, base3)).containsExactly(5L, 2L, 1L, null, null);

        // as_of = X+7：三列齐全
        Long[] r7 = runRetention("INSTALL", x, asOf7);
        assertThat(r7[1]).isNotNull();
        assertThat(r7[2]).isNotNull();
        assertThat(r7[3]).isNotNull();
        assertThat(delta(r7, base7)).containsExactly(5L, 2L, 1L, 1L, null);

        // ONBOARDING 行也写了（组为空也写），INSTALL 专用列在它上面为空
        Long[] onb = cohort("ONBOARDING", x);
        assertThat(onb).isNotNull();
        assertThat(Arrays.copyOfRange(onb, 1, 4)).containsOnlyNulls();

        // INSTALL 组人数 = 4.1 agg_daily_install（platform='all'）同日 new_installs（同一份数据，比绝对值）
        assertThat(service.rerun(x, x, List.of("install"), asOf7).outcome()).isEqualTo(Outcome.DONE);
        Long dailyAll = analyticsJdbc.queryForObject("SELECT new_installs FROM agg_daily_install "
                + "WHERE stat_date = ? AND platform = 'all'", Long.class, x);
        assertThat(r7[0]).isEqualTo(dailyAll);
        // 本平台那一行正好 5 人（P1–P5；排除项与升级不算）
        assertThat(analyticsJdbc.queryForObject("SELECT new_installs FROM agg_daily_install "
                + "WHERE stat_date = ? AND platform = ?", Long.class, x, platform)).isEqualTo(5L);

        // 汇总表没有可识别个人的列
        List<String> cols = analyticsJdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name IN ('agg_cohort_retention', 'agg_monthly_install_profile')", String.class);
        assertThat(cols).doesNotContain("user_id", "anonymous_id", "person_key", "user_key");
    }

    // ---------------------------------------------------------------- AC2：引导组 W1

    @Test
    void onboardingCohortW1Window() {
        LocalDate y = todayWib.minusDays(30);
        partitions(y.minusDays(3), y.plusDays(14));
        LocalDate asOf12 = y.plusDays(12);
        LocalDate asOf14 = y.plusDays(14);
        Long[] base12 = runRetention("ONBOARDING", y, asOf12);
        Long[] base14 = runRetention("ONBOARDING", y, asOf14);

        int[] openOffsets = {6, 7, 13, 14};
        for (int off : openOffsets) {
            long u = newUser().getId();
            String a = anon();
            event("onboarding_completed", a, u, y, 10);
            event("Application Opened", a, u, y.plusDays(off), 10);
        }
        // 首次引导早于 Y：只按第一次分组，不进 Y 组
        long again = newUser().getId();
        String ag = anon();
        event("onboarding_completed", ag, again, y.minusDays(3), 10);
        event("onboarding_completed", ag, again, y, 10);
        event("Application Opened", ag, again, y.plusDays(8), 10);
        // 兽医不算
        long vet = vetUser();
        String v = anon();
        event("onboarding_completed", v, vet, y, 10);
        event("Application Opened", v, vet, y.plusDays(8), 10);

        // as_of < Y+13：W1 为空
        Long[] r12 = runRetention("ONBOARDING", y, asOf12);
        assertThat(r12[4]).isNull();
        assertThat(Arrays.copyOfRange(r12, 1, 4)).as("d1/d3/d7 只用于 INSTALL").containsOnlyNulls();
        assertThat(delta(r12, base12)[0]).isEqualTo(4L);

        // as_of = Y+14：Y+6 不算、Y+7 与 Y+13 算、Y+14 不算
        Long[] r14 = runRetention("ONBOARDING", y, asOf14);
        assertThat(r14[4]).isNotNull();
        assertThat(delta(r14, base14)).containsExactly(4L, null, null, null, 2L);
    }

    // ---------------------------------------------------------------- AC4：回补与幂等

    @Test
    void rerunIsIdempotentAndNeverRegresses() {
        LocalDate x = todayWib.minusDays(40);
        partitions(x, x.plusDays(13));
        LocalDate asOf = x.plusDays(13);
        String p = anon();
        event("Application Installed", p, null, x, 9);
        event("Application Opened", p, null, x.plusDays(7), 9);
        long u = newUser().getId();
        String a = anon();
        event("onboarding_completed", a, u, x, 9);
        event("Application Opened", a, u, x.plusDays(9), 9);

        String q = "SELECT cohort_type, cohort_date, cohort_size, d1_retained, d3_retained, d7_retained, w1_retained "
                + "FROM agg_cohort_retention WHERE cohort_date BETWEEN ? AND ? ORDER BY cohort_type, cohort_date";
        assertThat(service.rerun(x, x.plusDays(5), List.of("retention"), asOf).outcome()).isEqualTo(Outcome.DONE);
        List<Map<String, Object>> first = analyticsJdbc.queryForList(q, x, x.plusDays(5));
        assertThat(first).hasSize(12);
        assertThat(service.rerun(x, x.plusDays(5), List.of("retention"), asOf).outcome()).isEqualTo(Outcome.DONE);
        assertThat(analyticsJdbc.queryForList(q, x, x.plusDays(5))).isEqualTo(first);

        // 补跑旧组日期（框架传当前的 as_of）：已满 N 天的值不会倒退成空
        Long[] before = cohort("INSTALL", x);
        assertThat(service.rerun(x, x, List.of("retention"), asOf).outcome()).isEqualTo(Outcome.DONE);
        Long[] after = cohort("INSTALL", x);
        assertThat(after).containsExactly(before);
        assertThat(after[1]).isNotNull();
        assertThat(after[3]).isNotNull();
        assertThat(cohort("ONBOARDING", x)[4]).isNotNull();
    }

    // ---------------------------------------------------------------- AC3：安装后建档（月）

    @Test
    void monthlyInstallProfile() {
        Period m = RollupPeriods.monthOf(todayWib.minusMonths(2));
        LocalDate next = m.end().plusDays(1);
        LocalDate asOf = todayWib.minusDays(1);
        partitions(m.start(), next);
        assertThat(service.rerun(m.end(), m.end(), List.of("install_profile"), asOf).outcome())
                .isEqualTo(Outcome.DONE);
        Map<String, Object> base = month(m.start());

        // Q1：月末安装（匿名，当晚登录）、次月建档 → 计新装、不计建档
        long u1 = newUser().getId();
        String a1 = anon();
        event("Application Installed", a1, null, m.end(), 9);
        link(a1, u1, m.end(), 10);
        event("pet_added", a1, u1, next, 10);
        // Q2：月中安装、同月建档
        long u2 = newUser().getId();
        String a2 = anon();
        event("Application Installed", a2, null, m.start().plusDays(5), 9);
        link(a2, u2, m.start().plusDays(5), 10);
        event("pet_added", a2, u2, m.start().plusDays(6), 10);
        // Q3：同月先建档后安装（重装）也算：不要求建档晚于安装
        long u3 = newUser().getId();
        String a3 = anon();
        link(a3, u3, m.start().plusDays(1), 8);
        event("pet_added", a3, u3, m.start().plusDays(2), 10);
        event("Application Installed", a3, u3, m.start().plusDays(4), 9);
        // 排除：非生产、可疑、匿名安装经关联归到兽医
        String np = anon();
        event("Application Installed", np, null, m.start().plusDays(3), 9, false, false);
        event("pet_added", np, null, m.start().plusDays(3), 10, false, false);
        String sus = anon();
        event("Application Installed", sus, null, m.start().plusDays(3), 9, true, true);
        long vet = vetUser();
        String v = anon();
        event("Application Installed", v, null, m.start().plusDays(3), 7);
        link(v, vet, m.start().plusDays(3), 8);
        event("pet_added", v, vet, m.start().plusDays(3), 9);
        // 点击即报的 pet_profile_create_submitted 不算建档
        long u4 = newUser().getId();
        String a4 = anon();
        event("Application Installed", a4, u4, m.start().plusDays(7), 9);
        event("pet_profile_create_submitted", a4, u4, m.start().plusDays(7), 10);

        assertThat(service.rerun(m.end(), next, List.of("install_profile"), asOf).outcome()).isEqualTo(Outcome.DONE);
        Map<String, Object> row = month(m.start());
        assertThat(n(row, "new_installs") - n(base, "new_installs")).isEqualTo(4);
        assertThat(n(row, "with_pet_profile") - n(base, "with_pet_profile")).isEqualTo(2);
        assertThat(row.get("is_complete")).isEqualTo(true);
        // 次月（as_of 之前已开始）也写了行；as_of 在次月内时未完成
        Map<String, Object> nextRow = month(next);
        assertThat(nextRow.get("is_complete")).isEqualTo(!RollupPeriods.monthOf(next).end().isAfter(asOf));

        // 月新装 = 当月 agg_daily_install all 行之和（同 S2 口径）
        assertThat(service.rerun(m.start(), m.end(), List.of("install"), asOf).outcome()).isEqualTo(Outcome.DONE);
        Long sum = analyticsJdbc.queryForObject("SELECT sum(new_installs) FROM agg_daily_install "
                + "WHERE stat_date BETWEEN ? AND ? AND platform = 'all'", Long.class, m.start(), m.end());
        assertThat(n(month(m.start()), "new_installs")).isEqualTo(sum);

        // 重跑幂等
        assertThat(service.rerun(m.end(), m.end(), List.of("install_profile"), asOf).outcome())
                .isEqualTo(Outcome.DONE);
        Map<String, Object> again = month(m.start());
        assertThat(n(again, "new_installs")).isEqualTo(n(row, "new_installs"));
        assertThat(n(again, "with_pet_profile")).isEqualTo(n(row, "with_pet_profile"));
    }
}
