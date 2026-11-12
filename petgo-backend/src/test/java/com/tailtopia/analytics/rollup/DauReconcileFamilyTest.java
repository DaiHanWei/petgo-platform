package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.rollup.DauReconcileFamily.Stored;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * L0：指标族 dau_reconcile（analytics-pipeline Story 4.2 AC3 / AC5）——SQL 文本口径、注册信息、整表重算只更新有变化的行、
 * 迁移文本、对账结果不推飞书。
 */
class DauReconcileFamilyTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 20);
    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-z_][a-z0-9_]*)");

    @Test
    void registeredAfterActivity() {
        DauReconcileFamily f = new DauReconcileFamily();
        assertThat(f.name()).isEqualTo("dau_reconcile");
        assertThat(f.dependsOn()).containsExactly("activity");
        assertThat(f.recomputeDays()).isEqualTo(3);
        List<String> order = RollupService.orderByDependencies(List.of(f, new SqlRollupFamily("activity"),
                new SqlRollupFamily("install"))).stream().map(RollupFamily::name).toList();
        assertThat(order.indexOf("activity")).isLessThan(order.indexOf("dau_reconcile"));
    }

    @Test
    void sqlIsSameCaliberAsGa4() {
        String sql = SqlRollupFamily.readScript("dau_reconcile");
        String lower = sql.toLowerCase(Locale.ROOT);
        // 不推算当前日期（T7）
        assertThat(lower).doesNotContain("now()", "current_date", "current_timestamp", "localtimestamp",
                "clock_timestamp", "transaction_timestamp", "statement_timestamp");
        assertThat(lower).doesNotContain("date(event_time", "event_time::date", "received_at");

        String stmt = new DauReconcileFamily().statements().getFirst();
        String body = stmt.toLowerCase(Locale.ROOT);
        assertThat(new DauReconcileFamily().statements()).hasSize(1);
        assertThat(stmt).startsWith("INSERT INTO agg_daily_dau_reconcile");
        // 按设备（匿名 id）去重、只 App 来源、排除可疑；不走归属视图、不做生产排除、不排除账号
        assertThat(body).contains("count(distinct d.anonymous_id)", "from events_detail d",
                "d.event_date = cast(:stat_date as date)", "d.event = 'application opened'", "d.source = 'app'",
                "not d.is_suspicious");
        assertThat(body).doesNotContain("v_events_person", "is_production", "tmp_excluded_users", "person_key");
        // 参照列取 activity 的 all 行；GA4 取快照
        assertThat(body).contains("from agg_daily_activity a", "a.platform = 'all'",
                "s.active_users_new_version", "from ga4_daily_snapshot s");
        assertThat(body).contains("on conflict (stat_date) do update");
        Matcher m = PARAM.matcher(stmt);
        while (m.find()) {
            assertThat(Set.of("stat_date", "computed_at")).contains(m.group(1));
        }
        // 汇总表不写可识别个人的列
        assertThat(stmt.lines().findFirst().orElseThrow() + stmt.lines().skip(1).findFirst().orElseThrow())
                .doesNotContain("user_id", "anonymous_id", "person_key");
    }

    private static Stored stored(int day, long self, Long ga4New, String diff, String cov, String status,
            LocalDate start, Long snapNew, Long snapAll) {
        return new Stored(D.plusDays(day), self, ga4New, diff == null ? null : new BigDecimal(diff),
                cov == null ? null : new BigDecimal(cov), status, start, snapNew, snapAll);
    }

    @Test
    void onlyChangedRowsAreUpdated() {
        // 第 0 天：已是最终状态（小数位不同也算相同）；第 1 天：新插入占位 NOT_STARTED、尚未算差值；
        // 第 2 天：GA4 补拉后快照变了，表里旧的 GA4 数要刷新
        List<Stored> rows = List.of(
                stored(0, 99, 90L, "0.1", "0.90", "NORMAL", D, 90L, 100L),
                stored(1, 100, 100L, null, null, "NOT_STARTED", null, 100L, 100L),
                stored(2, 100, null, null, null, "NO_GA4", D, 80L, 100L));
        List<Object[]> ups = DauReconcileFamily.updates(rows);

        assertThat(ups).hasSize(2);
        Object[] u1 = ups.get(0);
        assertThat(u1[5]).isEqualTo(D.plusDays(1));
        assertThat((BigDecimal) u1[1]).isEqualByComparingTo("0");
        assertThat((BigDecimal) u1[2]).isEqualByComparingTo("1");
        assertThat(u1[3]).isEqualTo("NORMAL");
        assertThat(u1[4]).isEqualTo(D);
        Object[] u2 = ups.get(1);
        assertThat(u2[5]).isEqualTo(D.plusDays(2));
        assertThat(u2[0]).isEqualTo(80L);
        assertThat((BigDecimal) u2[1]).isEqualByComparingTo("0.25");
        assertThat(u2[3]).isEqualTo("ABNORMAL");

        // 再算一次（模拟写回之后）不再有变化
        List<Stored> after = List.of(
                rows.get(0),
                stored(1, 100, 100L, "0.0000", "1.0000", "NORMAL", D, 100L, 100L),
                stored(2, 100, 80L, "0.2500", "0.8000", "ABNORMAL", D, 80L, 100L));
        assertThat(DauReconcileFamily.updates(after)).isEmpty();
    }

    @Test
    void snapshotRemovedMeansNoGa4() {
        List<Object[]> ups = DauReconcileFamily.updates(List.of(
                stored(0, 10, 10L, "0.0000", null, "NOT_STARTED", null, null, null)));
        assertThat(ups).singleElement().satisfies(u -> {
            assertThat(u[0]).isNull();
            assertThat(u[1]).isNull();
            assertThat(u[3]).isEqualTo("NO_GA4");
        });
    }

    @Test
    void migrationTablesStatusCheckAndGrants() throws IOException {
        String s;
        try (InputStream in = new ClassPathResource(
                "db/analytics-migration/V20261113_1310__ga4_snapshot_and_dau_reconcile.sql").getInputStream()) {
            s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String flat = s.replaceAll("\\s+", " ");
        assertThat(flat).contains("CREATE TABLE ga4_daily_snapshot", "CREATE TABLE agg_daily_dau_reconcile",
                "stat_date date PRIMARY KEY", "diff_pct numeric(9,4),", "coverage numeric(9,4),",
                "min_app_version varchar(16) NOT NULL", "status varchar(16) NOT NULL",
                "CHECK (status IN ('NOT_STARTED', 'NORMAL', 'ABNORMAL', 'ENDED', 'NO_GA4'))");
        for (DauReconcileCalculator.Status st : DauReconcileCalculator.Status.values()) {
            assertThat(s).contains("'" + st.name() + "'");
        }
        assertThat(s).contains("rolname = '${readerRole}'",
                "format('GRANT SELECT ON %I TO %I', 'ga4_daily_snapshot', '${readerRole}')",
                "format('GRANT SELECT ON %I TO %I', 'agg_daily_dau_reconcile', '${readerRole}')");
        assertThat(s).doesNotContain("TO analytics_reader").doesNotContainIgnoringCase("REFERENCES");
        assertThat(s).contains("COMMENT ON COLUMN agg_daily_dau_reconcile.coverage IS '新版覆盖率", "近似");
        // 不含可识别个人的列
        assertThat(flat).doesNotContain("user_id bigint", "anonymous_id varchar", "person_key ");
    }

    /** AC5：对账结果只存库，不推飞书——相关类不引用日报 / 飞书客户端，日报也不读对账表。 */
    @Test
    void reconcileIsNeverPushedToLark() throws IOException {
        Path main = Path.of("src/main/java/com/tailtopia");
        for (String cls : List.of("analytics/rollup/DauReconcileFamily.java",
                "analytics/rollup/DauReconcileCalculator.java", "analytics/rollup/Ga4DailySnapshotJob.java",
                "analytics/rollup/Ga4SnapshotRepository.java")) {
            String src = Files.readString(main.resolve(cls), StandardCharsets.UTF_8);
            assertThat(src).as(cls).doesNotContain("import com.tailtopia.admin.dailyreport.LarkWebhookClient",
                    "import com.tailtopia.admin.dailyreport.DailyReportService", "LarkWebhookClient lark");
        }
        try (var files = Files.walk(main.resolve("admin/dailyreport"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(p, StandardCharsets.UTF_8)).as(p.toString())
                        .doesNotContain("agg_daily_dau_reconcile", "DauReconcile");
            }
        }
    }
}
