package com.tailtopia.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：埋点库可用时的骨架（analytics-pipeline Story 1.1 AC1 / AC3）。
 *
 * <p>前置：本地 {@code createdb petgo_analytics_test}（CI 由 backend-ci.yml 建）。
 * 库不在时本类会红（管道停用）—— 那条路径由 {@link AnalyticsDbDownIntegrationTest} 覆盖。
 */
class AnalyticsBootstrapIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private TransactionTemplate analyticsTx;
    @Value("${spring.datasource.url}")
    private String businessUrl;
    @Value("${analytics.datasource.url}")
    private String analyticsUrl;

    private static String dbName(String jdbcUrl) {
        String tail = jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1);
        int q = tail.indexOf('?');
        return q < 0 ? tail : tail.substring(0, q);
    }

    private static boolean tableExists(JdbcTemplate t, String table) {
        return Boolean.TRUE.equals(t.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
    }

    @Test
    void pipelineEnabledAndMigrationsLiveInAnalyticsDb() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();

        assertThat(tableExists(analyticsJdbc, "flyway_schema_history")).isTrue();
        assertThat(analyticsJdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '20261113.1000' AND success",
                Integer.class)).isEqualTo(1);
        assertThat(tableExists(analyticsJdbc, "event_catalog")).isTrue();
        assertThat(tableExists(analyticsJdbc, "metric_definitions")).isTrue();

        // 业务库里绝没有埋点表，业务历史表里也没有埋点迁移
        assertThat(tableExists(jdbc, "event_catalog")).isFalse();
        assertThat(tableExists(jdbc, "metric_definitions")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '20261113.1000'", Integer.class)).isZero();
    }

    @Test
    void unqualifiedBeansHitBusinessDbQualifiedHitAnalyticsDb() {
        String business = jdbc.queryForObject("SELECT current_database()", String.class);
        String analytics = analyticsJdbc.queryForObject("SELECT current_database()", String.class);

        assertThat(business).isEqualTo(dbName(businessUrl));
        assertThat(analytics).isEqualTo(dbName(analyticsUrl)).isNotEqualTo(business);
        assertThat(namedJdbc.queryForObject("SELECT current_database()", new MapSqlParameterSource(), String.class))
                .isEqualTo(business);
        String viaTx = tx.execute(s -> jdbc.queryForObject("SELECT current_database()", String.class));
        String viaAnalyticsTx = analyticsTx.execute(
                s -> analyticsJdbc.queryForObject("SELECT current_database()", String.class));
        assertThat(viaTx).isEqualTo(business);
        assertThat(viaAnalyticsTx).isEqualTo(analytics);
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
