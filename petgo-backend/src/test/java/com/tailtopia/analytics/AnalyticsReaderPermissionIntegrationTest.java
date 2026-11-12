package com.tailtopia.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.support.ApiIntegrationTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * L1：AI 查数只读角色在真库上的权限（analytics-pipeline Story 4.8 AC4 / AC7，架构 A8 / FR-27）。
 *
 * <p>测试库没有 {@code analytics_reader}（迁移的条件授权在测试库跳过），所以本测试在<b>一个事务内</b>：
 * 用测试超级用户建一个临时角色 → 把各迁移里授权给 {@code ${readerRole}} 的 {@code DO} 块按临时角色名原样重放 →
 * {@code SET LOCAL ROLE} 成该角色逐项断言 → 整个事务回滚（建角色也一起撤销，不在实例上留任何东西）。
 * 测试账号不是超级用户时跳过（PG16 起只有 CREATEROLE 无法 SET ROLE 到自己建的角色）。
 *
 * <p>会话属性（{@code statement_timeout=30s}、{@code default_transaction_read_only}、{@code CONNECTION LIMIT 3}）
 * 只在以该账号登录时生效，{@code SET ROLE} 测不到，由 {@code ai-query-agent/deploy/sql/verify_analytics_reader.sql}
 * 在 stag / 生产核验（L2）。
 *
 * <p>前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}。
 */
class AnalyticsReaderPermissionIntegrationTest extends ApiIntegrationTest {

    static final String INSUFFICIENT_PRIVILEGE = "42501";

    static final List<String> WHITELIST = List.of(
            "events_detail", "identity_links", "v_events_person", "agg_progress", "ga4_daily_snapshot",
            "event_catalog", "metric_definitions",
            "agg_daily_activity", "agg_daily_install", "agg_daily_dau_reconcile", "agg_cohort_retention",
            "agg_monthly_install_profile", "agg_daily_placement", "agg_weekly_placement", "agg_monthly_placement",
            "agg_daily_push", "agg_weekly_push", "agg_monthly_push", "agg_daily_toko", "agg_weekly_toko",
            "agg_monthly_toko", "agg_daily_cart", "agg_daily_product", "agg_daily_ratio");

    static final Pattern DO_BLOCK = Pattern.compile("(?s)DO \\$\\$.*?END \\$\\$;");

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private DataSource analyticsDataSource;

    @Test
    void readerRoleSeesWhitelistOnlyAndCannotWrite() throws Exception {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        String role = "aiq_reader_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        try (Connection c = analyticsDataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                Assumptions.assumeTrue(canCreateRole(st), "测试账号不是超级用户，跳过");
                st.execute("CREATE ROLE " + role + " NOLOGIN NOINHERIT");
                for (String block : readerGrantBlocks()) {
                    st.execute(block.replace("${readerRole}", role));
                }
                List<String> partitions = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("""
                        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'events_raw'::regclass ORDER BY 1""")) {
                    while (rs.next()) {
                        partitions.add(rs.getString(1));
                    }
                }
                assertThat(partitions).as("events_raw 至少有 DEFAULT 分区").contains("events_raw_pdefault");
                boolean pg15 = serverVersion(st) >= 150000;
                // erasure_requests 不在埋点库：擦除登记在业务库 analytics_erasure_outbox，只读账号连不上业务库
                try (ResultSet rs = st.executeQuery("SELECT to_regclass('erasure_requests') IS NULL")) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).as("埋点库不应有 erasure_requests").isTrue();
                }

                st.execute("SET LOCAL ROLE " + role);

                List<String> denied = new ArrayList<>(List.of("events_raw", "pipeline_progress",
                        "detail_recompute_requests", "storage_watermarks", "rollup_rerun_requests"));
                denied.addAll(partitions);
                for (String t : denied) {
                    assertThat(sqlState(c, st, "SELECT 1 FROM " + t + " LIMIT 1"))
                            .as("只读角色不应能读 %s", t).isEqualTo(INSUFFICIENT_PRIVILEGE);
                }
                for (String t : WHITELIST) {
                    assertThat(sqlState(c, st, "SELECT * FROM " + t + " LIMIT 1"))
                            .as("只读角色应能读白名单表 %s", t).isNull();
                }
                for (String w : List.of(
                        "INSERT INTO agg_daily_activity DEFAULT VALUES",
                        "UPDATE agg_daily_activity SET dau_person = dau_person",
                        "DELETE FROM agg_daily_activity",
                        "TRUNCATE agg_daily_activity",
                        "INSERT INTO metric_definitions (code, name, definition) VALUES ('IT_X', 'x', 'x')",
                        "UPDATE events_detail SET is_suspicious = is_suspicious",
                        "DELETE FROM identity_links")) {
                    assertThat(sqlState(c, st, w)).as("写语句应被拒：%s", w).isEqualTo(INSUFFICIENT_PRIVILEGE);
                }
                if (pg15) {
                    assertThat(sqlState(c, st, "CREATE TABLE public.aiq_reader_it_t (i int)"))
                            .as("PG15+ public schema 不对 PUBLIC 开放 CREATE").isEqualTo(INSUFFICIENT_PRIVILEGE);
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** 在保存点内执行；返回 SQLState（成功为 null），失败回滚到保存点，事务继续可用。 */
    private static String sqlState(Connection c, Statement st, String sql) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            st.execute(sql);
            c.releaseSavepoint(sp);
            return null;
        } catch (SQLException e) {
            c.rollback(sp);
            return e.getSQLState();
        }
    }

    private static boolean canCreateRole(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                // 只认超级用户：PG16 起 CREATEROLE 建出的角色默认不给建者 SET 选项（createrole_self_grant 为空），
                // 后面的 SET LOCAL ROLE 会报 42501 而不是跳过（复审 2026-11-12）
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static int serverVersion(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT current_setting('server_version_num')::int")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 各迁移里引用 {@code ${readerRole}} 的条件授权块（原文，按文件名顺序）。 */
    static List<String> readerGrantBlocks() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/analytics-migration/*.sql");
        java.util.Arrays.sort(files, java.util.Comparator.comparing(Resource::getFilename));
        List<String> out = new ArrayList<>();
        for (Resource f : files) {
            String sql;
            try (InputStream in = f.getInputStream()) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher m = DO_BLOCK.matcher(sql);
            while (m.find()) {
                if (m.group().contains("${readerRole}")) {
                    out.add(m.group());
                }
            }
        }
        assertThat(out).as("应能从迁移里取到授权块").hasSizeGreaterThanOrEqualTo(10);
        return out;
    }
}
