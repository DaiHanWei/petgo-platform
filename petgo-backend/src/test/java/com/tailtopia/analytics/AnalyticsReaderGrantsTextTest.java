package com.tailtopia.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * L0：AI 查数只读账号的授权白名单与账号属性（analytics-pipeline Story 4.8 AC4 / AC7，架构 A8 / FR-27）。
 *
 * <p>测试库没有只读角色（迁移里的条件授权在 CI 跳过），所以这里断言<b>迁移文本</b>：
 * 授给 {@code ${readerRole}} 的表恰好是白名单，只授 SELECT，不写死角色名，不用 {@code ALTER DEFAULT PRIVILEGES}；
 * 埋点库里每张表都必须明确归入「授权」或「不授权」——新增表忘了决定授权时本测试失败。
 * 真库上的 {@code permission denied} 见 L1 {@code AnalyticsReaderPermissionIntegrationTest}。
 */
class AnalyticsReaderGrantsTextTest {

    /** 白名单：明细、关联、汇总、对账、口径（A8）。 */
    static final Set<String> GRANTED = Set.of(
            "event_catalog", "metric_definitions", "identity_links", "events_detail", "v_events_person",
            "agg_progress", "ga4_daily_snapshot",
            "agg_daily_activity", "agg_daily_install", "agg_daily_dau_reconcile",
            "agg_cohort_retention", "agg_monthly_install_profile",
            "agg_daily_placement", "agg_weekly_placement", "agg_monthly_placement",
            "agg_daily_push", "agg_weekly_push", "agg_monthly_push",
            "agg_daily_toko", "agg_weekly_toko", "agg_monthly_toko",
            "agg_daily_cart", "agg_daily_product", "agg_daily_ratio");

    /** 明确不授权：原始报文、管道内部状态、运维请求表（A8；erasure_requests 不在埋点库，见业务库 analytics_erasure_outbox）。 */
    static final Set<String> NOT_GRANTED = Set.of(
            "events_raw", "events_raw_pdefault", "pipeline_progress", "detail_recompute_requests",
            "storage_watermarks", "rollup_rerun_requests");

    static final Pattern GRANT = Pattern.compile(
            "GRANT\\s+(\\w+)\\s+ON\\s+%I\\s+TO\\s+%I'\\s*,\\s*'(\\w+)'\\s*,\\s*'\\$\\{readerRole}'");
    static final Pattern CREATED = Pattern.compile(
            "(?i)CREATE\\s+(?:TABLE|VIEW)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?!IF\\s)([a-z_][a-z0-9_]*)\\s*[(\\s]");

    private static String migrations;

    @BeforeAll
    static void load() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Resource f : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/analytics-migration/*.sql")) {
            try (InputStream in = f.getInputStream()) {
                sb.append(stripComments(new String(in.readAllBytes(), StandardCharsets.UTF_8))).append('\n');
            }
        }
        migrations = sb.toString();
    }

    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            int i = line.indexOf("--");
            out.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        return out.toString();
    }

    @Test
    void readerGrantsAreExactlyTheWhitelistAndSelectOnly() {
        Set<String> granted = new TreeSet<>();
        List<String> nonSelect = new ArrayList<>();
        Matcher m = GRANT.matcher(migrations);
        while (m.find()) {
            granted.add(m.group(2));
            if (!m.group(1).equals("SELECT")) {
                nonSelect.add(m.group(1) + " ON " + m.group(2));
            }
        }
        assertThat(nonSelect).as("只读角色只能授 SELECT").isEmpty();
        assertThat(granted).as("授给只读角色的表必须恰好是白名单").containsExactlyInAnyOrderElementsOf(GRANTED);
        assertThat(granted).doesNotContainAnyElementsOf(NOT_GRANTED);
        assertThat(migrations).doesNotContainIgnoringCase("ALTER DEFAULT PRIVILEGES")
                .doesNotContain("TO analytics_reader")
                .doesNotContainPattern("(?i)GRANT\\s+[^;]*ON\\s+ALL\\s+TABLES");
    }

    /**
     * 复审补：上面的白名单断言只认 {@code format('GRANT … %I TO %I', '<表>', '${readerRole}')} 一种写法。
     * 直接写 {@code GRANT SELECT ON events_raw TO ${readerRole};} 或授给 PUBLIC 会绕过它（L1 重放也只取 DO 块），
     * 所以要求迁移里出现的每个 GRANT 都是这种写法，且不出现 {@code TO PUBLIC}。
     */
    @Test
    void everyGrantUsesTheCheckedForm() {
        int allGrants = 0;
        Matcher any = Pattern.compile("(?i)\\bGRANT\\b").matcher(migrations);
        while (any.find()) {
            allGrants++;
        }
        int checked = 0;
        Matcher m = GRANT.matcher(migrations);
        while (m.find()) {
            checked++;
        }
        assertThat(allGrants).as("埋点库迁移里的 GRANT 必须写成 format('GRANT SELECT ON %%I TO %%I', '<表>', "
                + "'${readerRole}')，否则白名单校验看不到").isEqualTo(checked);
        assertThat(migrations).doesNotContainPattern("(?i)\\bTO\\s+PUBLIC\\b");
    }

    @Test
    void everyAnalyticsTableHasAnExplicitGrantDecision() {
        Set<String> created = new TreeSet<>();
        Matcher m = CREATED.matcher(migrations);
        while (m.find()) {
            created.add(m.group(1).toLowerCase());
        }
        created.add("events_raw_pdefault");
        List<String> undecided = created.stream()
                .filter(t -> !GRANTED.contains(t) && !NOT_GRANTED.contains(t)).toList();
        assertThat(undecided).as("以下表没有决定是否授给 AI 只读账号（加进 GRANTED 并在迁移授权，或加进 NOT_GRANTED）：%s",
                undecided).isEmpty();
        assertThat(created).as("白名单里的表都应在迁移里建出").containsAll(GRANTED);
    }

    @Test
    void createAnalyticsDbSetsReaderSessionDefaults() throws Exception {
        Path p = Path.of("..", "scripts", "ops", "create-analytics-db.sql");
        Assumptions.assumeTrue(Files.isRegularFile(p), "scripts/ops/create-analytics-db.sql 不存在，跳过");
        String sql = stripComments(Files.readString(p, StandardCharsets.UTF_8));
        assertThat(sql).containsPattern("ALTER ROLE :\"reader_role\" WITH LOGIN NOINHERIT[^;]*CONNECTION LIMIT 3");
        assertThat(sql).contains(
                "ALTER ROLE :\"reader_role\" SET statement_timeout = '30s';",
                "ALTER ROLE :\"reader_role\" SET idle_in_transaction_session_timeout = '60s';",
                "ALTER ROLE :\"reader_role\" SET default_transaction_read_only = on;",
                "REVOKE ALL ON DATABASE :\"db_name\" FROM PUBLIC;",
                "GRANT CONNECT ON DATABASE :\"db_name\" TO :\"reader_role\";");
        assertThat(sql).doesNotContainIgnoringCase("ALTER DEFAULT PRIVILEGES");
        assertThat(sql).doesNotContainPattern("(?i)PASSWORD\\s+'");
    }
}
