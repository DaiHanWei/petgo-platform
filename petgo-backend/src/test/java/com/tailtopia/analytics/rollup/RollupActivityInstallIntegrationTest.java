package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.analytics.rollup.RollupService.Outcome;
import com.tailtopia.analytics.rollup.RollupService.RunResult;
import com.tailtopia.analytics.store.DetailPartitions;
import com.tailtopia.analytics.store.EventTimeCorrector;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * L1：汇总框架 + activity / install 指标族（analytics-pipeline Story 4.1 AC1 / AC3 / AC4 / AC5 / AC6 / AC7 / AC8），
 * 真业务库 + 埋点测试库 {@code petgo_analytics_test}（前置同 {@code AnalyticsBootstrapIntegrationTest}：本地
 * {@code createdb petgo_analytics_test}）。
 *
 * <ul>
 *   <li>定时关闭（基类 {@code analytics.rollup.cron=-}），用例手工 new 一个带 mock 告警的 {@link RollupService}，直接调
 *       {@link RollupService#rerun} / {@link RollupService#runScheduled}。</li>
 *   <li>隔离：共享库里别的测试也会往明细写事件，所以本类的事件一律用<b>本用例独有的平台值</b>，断言只看该平台那一行
 *       （{@code all} 行混有别人的数据，只断言「不小于」）。明细 / 关联行按 {@code message_id} 前缀清理。</li>
 *   <li>{@code event_date} 由测试直接给成 WIB 日（明细层转换另有 1.6 的测试覆盖）。</li>
 * </ul>
 */
class RollupActivityInstallIntegrationTest extends ApiIntegrationTest {

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
    @Autowired
    private RollupService springService;

    private OpsAlertService alerts;
    private RollupProgressRepository progress;
    private String prefix;
    private String platform;
    private LocalDate asOf;
    private LocalDate day;
    private LocalDate nextDay;

    @BeforeEach
    void setUp() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        assertThat(springService.familyNames()).containsExactly("activity", "install", "dau_reconcile", "retention",
                "install_profile");
        alerts = mock(OpsAlertService.class);
        progress = new RollupProgressRepository(analyticsJdbc);
        long n = SEQ.incrementAndGet();
        prefix = "it-ru-" + n + "-";
        platform = "it" + Long.toString(n % 1_000_000_000_000L, 36);
        Instant now = Instant.now();
        LocalDate todayWib = EventTimeCorrector.wibDate(now);
        asOf = todayWib.minusDays(1);
        day = asOf.minusDays(1);
        nextDay = asOf;
        analyticsTx.executeWithoutResult(s -> {
            for (LocalDate d = todayWib.minusDays(3); !d.isAfter(todayWib.plusDays(1)); d = d.plusDays(1)) {
                analyticsJdbc.execute("CREATE TABLE IF NOT EXISTS " + DetailPartitions.partitionName(d)
                        + " PARTITION OF " + DetailPartitions.PARENT + " FOR VALUES FROM ('" + d + "') TO ('"
                        + d.plusDays(1) + "')");
            }
        });
    }

    @AfterEach
    void cleanUp() {
        analyticsJdbc.update("DELETE FROM identity_links WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ?", prefix + "%");
        analyticsJdbc.update("DELETE FROM agg_daily_activity WHERE platform = ?", platform);
        analyticsJdbc.update("DELETE FROM agg_daily_install WHERE platform = ?", platform);
        analyticsJdbc.update("DELETE FROM agg_progress WHERE family LIKE 'it\\_fail\\_%'");
    }

    // ---------------------------------------------------------------- 造数

    private RollupService service(List<RollupFamily> families) {
        return new RollupService(state, analyticsJdbc, analyticsTx, businessJdbc, progress, families,
                AnalyticsRollupProperties.defaults(), alerts, "test", Clock.systemUTC());
    }

    private RollupService service() {
        return service(List.of(new SqlRollupFamily("activity"), new SqlRollupFamily("install")));
    }

    private static Instant wib(LocalDate d, int hour, int minute) {
        return d.atTime(hour, minute).atZone(EventTimeCorrector.WIB).toInstant();
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static String anon() {
        return UUID.randomUUID().toString();
    }

    private void event(String event, String anon, Long userId, Instant at, LocalDate eventDate, boolean production,
            boolean suspicious, String source) {
        analyticsJdbc.update("""
                INSERT INTO events_detail (message_id, event, client_ts, event_time, event_date, is_suspicious, source,
                                           anonymous_id, user_id, user_key, platform, is_production, properties)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)""",
                prefix + "det-" + SEQ.incrementAndGet(), event, utc(at), utc(at), eventDate, suspicious, source, anon,
                userId, userId == null ? null : AnalyticsDistinctId.of(userId), platform, production);
    }

    /** 生产、非可疑、APP；event_date 取发生时刻的 WIB 日。 */
    private void event(String event, String anon, Long userId, Instant at) {
        event(event, anon, userId, at, EventTimeCorrector.wibDate(at), true, false, "APP");
    }

    private void link(String anon, long userId, Instant at) {
        analyticsJdbc.update("INSERT INTO identity_links (anonymous_id, user_id, user_key, linked_at, message_id) "
                + "VALUES (?, ?, ?, ?, ?)", anon, userId, AnalyticsDistinctId.of(userId), utc(at),
                prefix + "link-" + SEQ.incrementAndGet());
    }

    private long vetUser() {
        long id = newUser().getId();
        businessJdbc.update("UPDATE users SET role = 'VET' WHERE id = ?", id);
        return id;
    }

    private long virtualUser() {
        long id = newUser().getId();
        businessJdbc.update("UPDATE users SET account_type = 'VIRTUAL' WHERE id = ?", id);
        return id;
    }

    private Map<String, Object> activity(LocalDate d, String p) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT dau_person, dau_logged_in FROM agg_daily_activity WHERE stat_date = ? AND platform = ?", d, p);
        return rows.isEmpty() ? Map.of("dau_person", 0L, "dau_logged_in", 0L) : rows.get(0);
    }

    private Map<String, Object> install(LocalDate d, String p) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT new_installs, signups FROM agg_daily_install WHERE stat_date = ? AND platform = ?", d, p);
        return rows.isEmpty() ? Map.of("new_installs", 0L, "signups", 0L) : rows.get(0);
    }

    private static long n(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }

    // ---------------------------------------------------------------- AC8 构造数据集

    /**
     * 安装（未登录）→ 当晚注册 → 次日打开算同一个人；登出后的匿名打开归上一个账号；23:30 WIB 的事件归 WIB 当天、
     * 00:30 WIB（UTC 前一天 17:30）的事件归 WIB 次日；非生产、可疑、H5、兽医、虚拟号、匿名经关联归到兽医都被排除。
     */
    @Test
    void attributionAndExclusions() {
        long u1 = newUser().getId();
        long vet = vetUser();
        long virtual = virtualUser();
        String a = anon();

        // 人 U1：D 日 10:00 未登录安装 + 首启 → 20:00 登录（关联 A→U1）+ 注册 → 20:02 登录态打开 → 23:30 登出后匿名打开
        event("Application Installed", a, null, wib(day, 10, 0));
        event("Application Opened", a, null, wib(day, 10, 0).plusSeconds(1));
        link(a, u1, wib(day, 20, 0));
        event("signup_succeeded", a, u1, wib(day, 20, 1));
        event("Application Opened", a, u1, wib(day, 20, 2));
        Instant lateNight = wib(day, 23, 30);
        assertThat(lateNight.atOffset(ZoneOffset.UTC).getHour()).isEqualTo(16);
        event("Application Opened", a, null, lateNight);
        // D+1：U1 登录态打开
        event("Application Opened", a, u1, wib(nextDay, 9, 0));

        // 人 B：从未关联；D+1 00:30 WIB（= D 日 17:30 UTC）打开，event_date 给 WIB 日 D+1
        String b = anon();
        Instant pastMidnight = wib(nextDay, 0, 30);
        assertThat(pastMidnight.atOffset(ZoneOffset.UTC).toLocalDate()).isEqualTo(day);
        event("Application Opened", b, null, pastMidnight, nextDay, true, false, "APP");

        // 排除项（都在 D 日）
        event("Application Opened", anon(), null, wib(day, 11, 0), day, false, false, "APP"); // 非生产
        event("Application Installed", anon(), null, wib(day, 11, 0), day, false, false, "APP");
        event("Application Opened", anon(), null, wib(day, 11, 0), day, true, true, "APP"); // 可疑
        event("Application Opened", anon(), null, wib(day, 11, 0), day, true, false, "H5"); // H5 访客
        event("Application Opened", anon(), vet, wib(day, 12, 0)); // 兽医自己的事件（假设生产标记漏判）
        event("Application Opened", anon(), virtual, wib(day, 12, 0)); // 虚拟号
        String e = anon(); // 匿名事件经关联归到兽医（1-6 复审 R4）：登录前安装 / 首启、登出后打开
        event("Application Installed", e, null, wib(day, 8, 0));
        event("Application Opened", e, null, wib(day, 8, 0).plusSeconds(1));
        link(e, vet, wib(day, 9, 0));
        event("Application Opened", e, null, wib(day, 18, 0));
        event("Application Updated", anon(), null, wib(day, 13, 0)); // 升级不算新装

        // 共享测试库里的进度可能停在很久以前：重跑与进度不相连时不推进（复审 R2），这里从「从未成功」开始
        analyticsJdbc.update("DELETE FROM agg_progress WHERE family IN ('activity', 'install')");
        RunResult r = service().rerun(day, nextDay, null, asOf);
        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.units()).isEqualTo(4);

        // D：U1 一人（登录前首启、登录态、登出后打开合并）；新装 1（未登录安装归到首次登录的 U1）、注册 1
        Map<String, Object> actD = activity(day, platform);
        assertThat(n(actD, "dau_person")).isEqualTo(1);
        assertThat(n(actD, "dau_logged_in")).isEqualTo(1);
        Map<String, Object> insD = install(day, platform);
        assertThat(n(insD, "new_installs")).isEqualTo(1);
        assertThat(n(insD, "signups")).isEqualTo(1);

        // D+1：U1（同一人，不再算新装）+ 从未关联的 B
        Map<String, Object> actD1 = activity(nextDay, platform);
        assertThat(n(actD1, "dau_person")).isEqualTo(2);
        assertThat(n(actD1, "dau_logged_in")).isEqualTo(1);
        Map<String, Object> insD1 = install(nextDay, platform);
        assertThat(n(insD1, "new_installs")).isZero();
        assertThat(n(insD1, "signups")).isZero();

        // all 行存在且整体去重（共享库里有别人的数据，只断言不小于本平台）
        assertThat(n(activity(day, "all"), "dau_person")).isGreaterThanOrEqualTo(1);
        assertThat(n(install(day, "all"), "new_installs")).isGreaterThanOrEqualTo(1);

        // 进度推进到 D+1，last_error 清空
        assertThat(progress.find("activity").orElseThrow().lastDoneDate()).isAfterOrEqualTo(nextDay);
        assertThat(progress.find("install").orElseThrow().lastError()).isNull();

        // 汇总表没有可识别个人的列
        List<String> cols = analyticsJdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name IN ('agg_daily_activity', 'agg_daily_install')", String.class);
        assertThat(cols).doesNotContain("user_id", "anonymous_id", "person_key", "user_key");
    }

    /** 首次注册早于安装归属：按人，跨两天只在首次那天计一次；视图 person_key 与预期一致（AC5）。 */
    @Test
    void viewAttributesBeforeAndAfterLinks() {
        long u1 = newUser().getId();
        long u2 = newUser().getId();
        String a = anon();
        event("Application Opened", a, null, wib(day, 7, 0));   // 之前没有关联、之后有 → 第一个（U1）
        link(a, u1, wib(day, 8, 0));
        event("Application Opened", a, null, wib(day, 9, 0));   // 之前有 → 最近一次（U1）
        link(a, u2, wib(day, 10, 0));
        event("Application Opened", a, null, wib(day, 11, 0));  // 之前有 → 最近一次（U2）
        String never = anon();
        event("Application Opened", never, null, wib(day, 12, 0)); // 从未关联 → 匿名 id

        List<String> keys = analyticsJdbc.queryForList("SELECT person_key FROM v_events_person "
                + "WHERE event_date = ? AND message_id LIKE ? ORDER BY event_time", String.class, day, prefix + "%");
        assertThat(keys).containsExactly("u:" + u1, "u:" + u1, "u:" + u2, "a:" + never);
    }

    // ---------------------------------------------------------------- 幂等与失败

    @Test
    void rerunTwiceIsIdempotent() {
        long u1 = newUser().getId();
        String a = anon();
        event("Application Installed", a, null, wib(day, 10, 0));
        link(a, u1, wib(day, 10, 5));
        event("signup_succeeded", a, u1, wib(day, 10, 6));
        event("Application Opened", a, u1, wib(day, 10, 7));
        event("Application Opened", anon(), null, wib(nextDay, 10, 0));

        assertThat(service().rerun(day, nextDay, null, asOf).outcome()).isEqualTo(Outcome.DONE);
        String q = "SELECT stat_date, platform, dau_person, dau_logged_in FROM agg_daily_activity "
                + "WHERE platform = ? ORDER BY stat_date";
        String qi = "SELECT stat_date, platform, new_installs, signups FROM agg_daily_install "
                + "WHERE platform = ? ORDER BY stat_date";
        List<Map<String, Object>> first = analyticsJdbc.queryForList(q, platform);
        List<Map<String, Object>> firstI = analyticsJdbc.queryForList(qi, platform);
        assertThat(service().rerun(day, nextDay, null, asOf).outcome()).isEqualTo(Outcome.DONE);
        assertThat(analyticsJdbc.queryForList(q, platform)).isEqualTo(first).hasSize(2);
        assertThat(analyticsJdbc.queryForList(qi, platform)).isEqualTo(firstI);

        // 某平台从有到无：删掉 D+1 的事件后重跑，旧行被删掉（先删后插）
        analyticsJdbc.update("DELETE FROM events_detail WHERE message_id LIKE ? AND event_date = ?", prefix + "%",
                nextDay);
        assertThat(service().rerun(nextDay, nextDay, List.of("activity"), asOf).outcome()).isEqualTo(Outcome.DONE);
        assertThat(analyticsJdbc.queryForList(q, platform)).hasSize(1);
    }

    /** 失败：数据与进度同一事务回滚；last_error 记类名；回滚之后才告警；下一次运行补上（AC3）。 */
    @Test
    void failureRollsBackDataAndProgressThenCatchesUp() {
        String fam = "it_fail_" + Long.toString(SEQ.incrementAndGet() % 1_000_000L, 36);
        boolean[] broken = {true};
        RollupFamily failing = new RollupFamily() {
            @Override
            public String name() {
                return fam;
            }

            @Override
            public void computeDay(LocalDate statDate, LocalDate asOfDate, RollupContext ctx) {
                ctx.jdbcTemplate().update("INSERT INTO agg_daily_activity (stat_date, platform, dau_person, "
                        + "dau_logged_in, computed_at) VALUES (?, ?, 1, 1, ?)", statDate, platform,
                        utc(ctx.runAt()));
                Long excluded = ctx.jdbcTemplate().queryForObject("SELECT count(*) FROM tmp_excluded_users",
                        Long.class);
                assertThat(excluded).isEqualTo(ctx.excludedUserCount());
                if (broken[0]) {
                    ctx.jdbcTemplate().queryForObject("SELECT count(*) FROM it_no_such_table", Long.class);
                }
            }
        };
        RollupService svc = service(List.of(failing));

        RunResult r1 = svc.runScheduled(asOf);
        assertThat(r1.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r1.failedFamilies()).containsExactly(fam);
        LocalDate first = asOf.minusDays(2);
        // 写进去的行随事务回滚；进度没推进；last_error 只有类名
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM agg_daily_activity WHERE platform = ?",
                Long.class, platform)).isZero();
        RollupProgressRepository.Progress p = progress.find(fam).orElseThrow();
        assertThat(p.lastDoneDate()).isNull();
        assertThat(p.lastError()).contains("BadSqlGrammarException").doesNotContain("it_no_such_table");
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-rollup:" + fam + ":" + first), anyString());

        broken[0] = false;
        RunResult r2 = svc.runScheduled(asOf);
        assertThat(r2.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r2.units()).isEqualTo(3);
        RollupProgressRepository.Progress p2 = progress.find(fam).orElseThrow();
        assertThat(p2.lastDoneDate()).isEqualTo(asOf);
        assertThat(p2.lastError()).isNull();
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM agg_daily_activity WHERE platform = ?",
                Long.class, platform)).isEqualTo(3);

        // 重算旧日期不让进度倒退
        assertThat(svc.rerun(first, first, null, asOf).outcome()).isEqualTo(Outcome.DONE);
        assertThat(progress.find(fam).orElseThrow().lastDoneDate()).isEqualTo(asOf);
    }

    // ---------------------------------------------------------------- 手动重跑入口（AC4）

    @Test
    void rerunRequestsAreConsumed() {
        RollupRerunJob job = new RollupRerunJob(service(), analyticsJdbc, alerts, "test", Clock.systemUTC());
        Long ok = analyticsJdbc.queryForObject("INSERT INTO rollup_rerun_requests (from_date, to_date, families) "
                + "VALUES (?, ?, '{activity}') RETURNING id", Long.class, day, asOf);
        Long tooOld = analyticsJdbc.queryForObject("INSERT INTO rollup_rerun_requests (from_date, to_date) "
                + "VALUES (?, ?) RETURNING id", Long.class, asOf.minusDays(200), asOf);
        Long future = analyticsJdbc.queryForObject("INSERT INTO rollup_rerun_requests (from_date, to_date) "
                + "VALUES (?, ?) RETURNING id", Long.class, asOf, asOf.plusDays(1));
        Long unknown = analyticsJdbc.queryForObject("INSERT INTO rollup_rerun_requests (from_date, to_date, families) "
                + "VALUES (?, ?, '{dau}') RETURNING id", Long.class, asOf, asOf);
        try {
            for (int i = 0; i < 50 && pending(ok, tooOld, future, unknown); i++) {
                job.runOnce();
            }
            assertThat(status(ok)).isEqualTo("DONE");
            assertThat(status(tooOld)).isEqualTo("REJECTED");
            assertThat(status(future)).isEqualTo("REJECTED");
            assertThat(status(unknown)).isEqualTo("REJECTED");
            assertThat(analyticsJdbc.queryForObject("SELECT error_code FROM rollup_rerun_requests WHERE id = ?",
                    String.class, unknown)).isEqualTo(RollupService.REJECT_UNKNOWN_FAMILY);
            verify(alerts).send(eq(OpsChannel.TECH), eq(RollupRerunJob.ALERT_REJECTED + tooOld), anyString());
        } finally {
            analyticsJdbc.update("DELETE FROM rollup_rerun_requests WHERE id IN (?, ?, ?, ?)", ok, tooOld, future,
                    unknown);
        }
    }

    private boolean pending(Long... ids) {
        for (Long id : ids) {
            String s = status(id);
            if ("PENDING".equals(s) || "RUNNING".equals(s)) {
                return true;
            }
        }
        return false;
    }

    private String status(Long id) {
        return analyticsJdbc.queryForObject("SELECT status FROM rollup_rerun_requests WHERE id = ?", String.class, id);
    }
}
