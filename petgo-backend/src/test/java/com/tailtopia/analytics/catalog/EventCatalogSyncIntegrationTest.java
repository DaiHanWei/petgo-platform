package com.tailtopia.analytics.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.catalog.EventCatalog.EventDefinition;
import com.tailtopia.analytics.catalog.MetricDefinitions.MetricDefinition;
import com.tailtopia.analytics.config.AnalyticsDataSourceConfig;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：启动同步与重复同步（analytics-pipeline Story 1.2 AC5 / AC6）。
 *
 * <p>前置同 {@code AnalyticsBootstrapIntegrationTest}：本地 {@code createdb petgo_analytics_test}（CI 由 backend-ci.yml 建）。
 * 每个用例结束后用真实 yaml 再同步一次，把表恢复成 yaml 的投影。
 */
class EventCatalogSyncIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AnalyticsPipelineState state;
    @Autowired
    private EventCatalog catalog;
    @Autowired
    private MetricDefinitions metrics;
    @Autowired
    private CatalogSync sync;
    @Autowired
    @Qualifier(AnalyticsDataSourceConfig.QUALIFIER)
    private JdbcTemplate analyticsJdbc;

    @AfterEach
    void restore() {
        sync.sync(catalog.events(), metrics.metrics());
    }

    private int eventRows() {
        return analyticsJdbc.queryForObject("SELECT count(*) FROM event_catalog", Integer.class);
    }

    @Test
    void startupSyncProjectsYaml() {
        assertThat(state.isEnabled()).as("埋点测试库可用时管道应启用，原因=%s", state.reason()).isTrue();
        assertThat(catalog.isLoaded()).isTrue();

        assertThat(eventRows()).isEqualTo(catalog.events().size());
        assertThat(analyticsJdbc.queryForObject(
                "SELECT status FROM event_catalog WHERE event = 'Application Opened'", String.class))
                .isEqualTo("PLANNED");
        // properties 是 jsonb 数组，用户标识标记保留
        assertThat(analyticsJdbc.queryForObject("""
                SELECT count(*) FROM event_catalog, jsonb_array_elements(properties) p
                WHERE (p ->> 'user_identifier')::boolean AND p ->> 'name' = 'target_user_id'""", Integer.class))
                .isPositive();
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM metric_definitions", Integer.class))
                .isEqualTo(metrics.metrics().size());
        assertThat(analyticsJdbc.queryForObject(
                "SELECT changelog -> 0 ->> 'date' FROM metric_definitions WHERE code = 'S1'", String.class))
                .isEqualTo("2026-11-13");
    }

    @Test
    void changedDescriptionIsUpdated() {
        List<EventDefinition> changed = new ArrayList<>(catalog.events());
        EventDefinition first = changed.get(0);
        changed.set(0, new EventDefinition(first.name(), first.source(), first.status(), first.domain(),
                "改过的描述", first.codeRef(), first.properties()));

        sync.sync(changed, metrics.metrics());

        assertThat(analyticsJdbc.queryForObject("SELECT description FROM event_catalog WHERE event = ?",
                String.class, first.name())).isEqualTo("改过的描述");
    }

    @Test
    void rowsMissingFromYamlAreDeleted() {
        analyticsJdbc.update("INSERT INTO event_catalog (event, source, status, description) "
                + "VALUES ('it_stale_event', 'APP', 'ACTIVE', 'x')");
        analyticsJdbc.update("INSERT INTO metric_definitions (code, name, definition) VALUES ('IT_STALE', 'x', 'x')");

        sync.sync(catalog.events(), metrics.metrics());

        assertThat(analyticsJdbc.queryForObject(
                "SELECT count(*) FROM event_catalog WHERE event = 'it_stale_event'", Integer.class)).isZero();
        assertThat(analyticsJdbc.queryForObject(
                "SELECT count(*) FROM metric_definitions WHERE code = 'IT_STALE'", Integer.class)).isZero();
        assertThat(eventRows()).isEqualTo(catalog.events().size());
    }

    /** Story 4.8 AC3：新列 team_ref / tables 随启动同步写入（V20261113_1430 补列）。 */
    @Test
    void teamRefAndTablesAreSynced() {
        assertThat(analyticsJdbc.queryForObject("SELECT team_ref FROM metric_definitions WHERE code = 'S1'",
                String.class)).isEqualTo("S1");
        assertThat(analyticsJdbc.queryForObject(
                "SELECT tables @> '[\"agg_daily_activity\"]'::jsonb FROM metric_definitions WHERE code = 'S1'", Boolean.class))
                .isTrue();
        assertThat(analyticsJdbc.queryForObject(
                "SELECT team_ref IS NULL AND exclusions IS NOT NULL FROM metric_definitions WHERE code = '_rules'",
                Boolean.class)).isTrue();
    }

    @Test
    void repeatedSyncIsIdempotentAndKeepsUpdatedAt() {
        sync.sync(catalog.events(), metrics.metrics());
        Object before = analyticsJdbc.queryForObject("SELECT max(updated_at) FROM event_catalog", Object.class);

        sync.sync(catalog.events(), metrics.metrics());

        assertThat(eventRows()).isEqualTo(catalog.events().size());
        Object after = analyticsJdbc.queryForObject("SELECT max(updated_at) FROM event_catalog", Object.class);
        assertThat(after).as("内容没变不改写 updated_at").isEqualTo(before);
        List<MetricDefinition> m = metrics.metrics();
        assertThat(analyticsJdbc.queryForObject("SELECT count(*) FROM metric_definitions", Integer.class))
                .isEqualTo(m.size());
    }
}
