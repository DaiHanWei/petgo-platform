package com.tailtopia.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：埋点启动引导的停用分支（analytics-pipeline Story 1.1 AC4）—— 无 Spring 上下文、无 DB。
 * 真库下的迁移 / 历史表位置由 L1 {@code AnalyticsBootstrapIntegrationTest} 覆盖。
 */
class AnalyticsBootstrapTest {

    /** 2026-11-12 23:30 WIB = 16:30 UTC：去重键按 UTC 日。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T16:30:00Z"), ZoneId.of("Asia/Jakarta"));

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final OpsAlertService alerts = mock(OpsAlertService.class);

    private static DataSource healthyDataSource() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.isValid(anyInt())).thenReturn(true);
        return ds;
    }

    private AnalyticsBootstrap bootstrap(DataSource ds, String role, Consumer<DataSource> migrator) {
        return new AnalyticsBootstrap(ds, role, "stag", state, alerts, migrator, CLOCK);
    }

    @Test
    void healthyDbRunsMigrationAndStaysEnabled() throws SQLException {
        AtomicInteger runs = new AtomicInteger();
        bootstrap(healthyDataSource(), "analytics_reader", ds -> runs.incrementAndGet()).afterPropertiesSet();

        assertThat(runs).hasValue(1);
        assertThat(state.isEnabled()).isTrue();
        verify(alerts, never()).send(any(), anyString(), anyString());
    }

    @Test
    void connectionFailureDisablesWithDbUnavailableAndAlertsTechOnce() throws SQLException {
        DataSource ds = mock(DataSource.class);
        SQLException root = new SQLException("FATAL: database \"petgo_analytics\" does not exist "
                + "jdbc:postgresql://secret-host/petgo_analytics user=analytics_writer password=hunter2", "3D000");
        when(ds.getConnection()).thenThrow(new SQLTransientConnectionException("analytics - timeout", null, root));
        AtomicInteger runs = new AtomicInteger();

        bootstrap(ds, "analytics_reader", d -> runs.incrementAndGet()).afterPropertiesSet();

        assertThat(runs).hasValue(0);
        assertThat(state.isEnabled()).isFalse();
        assertThat(state.reason()).contains(Reason.DB_UNAVAILABLE);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-pipeline-disabled:2026-11-12"), text.capture());
        assertThat(text.getValue()).contains("DB_UNAVAILABLE", "stag", "3D000")
                .doesNotContain("secret-host", "hunter2", "jdbc:", "analytics_writer");
        assertThat(state.detail().orElseThrow()).doesNotContain("secret-host", "hunter2");
    }

    @Test
    void invalidConnectionCountsAsDbUnavailable() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.isValid(anyInt())).thenReturn(false);

        bootstrap(ds, "analytics_reader", d -> { }).afterPropertiesSet();

        assertThat(state.reason()).contains(Reason.DB_UNAVAILABLE);
    }

    @Test
    void flywayFailureDisablesWithMigrationFailed() throws SQLException {
        bootstrap(healthyDataSource(), "analytics_reader", d -> {
            throw new FlywayException("Migration V20261113_1000 failed");
        }).afterPropertiesSet();

        assertThat(state.isEnabled()).isFalse();
        assertThat(state.reason()).contains(Reason.MIGRATION_FAILED);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-pipeline-disabled:2026-11-12"), anyString());
    }

    @Test
    void anyRuntimeFailureNeverPropagates() throws SQLException {
        bootstrap(healthyDataSource(), "analytics_reader", d -> {
            throw new IllegalStateException("boom");
        }).afterPropertiesSet();

        assertThat(state.reason()).contains(Reason.MIGRATION_FAILED);
    }

    @Test
    void alertFailureNeverPropagates() {
        DataSource ds = mock(DataSource.class);
        try {
            when(ds.getConnection()).thenThrow(new SQLException("down"));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        doThrow(new RuntimeException("lark down")).when(alerts)
                .send(any(), anyString(), anyString());

        bootstrap(ds, "analytics_reader", d -> { }).afterPropertiesSet();

        assertThat(state.reason()).contains(Reason.DB_UNAVAILABLE);
    }

    @Test
    void illegalReaderRoleIsRejectedBeforeMigrating() throws SQLException {
        AtomicInteger runs = new AtomicInteger();
        bootstrap(healthyDataSource(), "x'; DROP TABLE event_catalog; --", d -> runs.incrementAndGet())
                .afterPropertiesSet();

        assertThat(runs).hasValue(0);
        assertThat(state.reason()).contains(Reason.MIGRATION_FAILED);
    }

    @Test
    void alreadyDisabledDoesNotAlertAgain() {
        state.disable(Reason.CATALOG_INVALID, "earlier");
        DataSource ds = mock(DataSource.class);
        try {
            when(ds.getConnection()).thenThrow(new SQLException("down"));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }

        bootstrap(ds, "analytics_reader", d -> { }).afterPropertiesSet();

        assertThat(state.reason()).contains(Reason.CATALOG_INVALID);
        verify(alerts, never()).send(any(), anyString(), anyString());
    }

    @Test
    void describeKeepsClassNamesAndSqlStateOnly() {
        String d = AnalyticsBootstrap.describe(new RuntimeException("password=x",
                new SQLException("jdbc:postgresql://h/db", "28P01")));
        assertThat(d).isEqualTo("RuntimeException <- SQLException, SQLState 28P01");
    }
}
