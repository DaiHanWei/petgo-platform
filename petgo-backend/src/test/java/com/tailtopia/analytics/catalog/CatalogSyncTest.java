package com.tailtopia.analytics.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.EventCatalog.PropertyDefinition;
import com.tailtopia.analytics.catalog.MetricDefinitions.ChangelogEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** L0：同步的跳过 / 失败处理与 jsonb 形状（analytics-pipeline Story 1.2 AC5；真库行为见 L1 EventCatalogSyncIntegrationTest）。 */
class CatalogSyncTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T02:00:00Z"), ZoneOffset.UTC);

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private CatalogSync sync(boolean loadCatalog) {
        EventCatalog catalog = new EventCatalog(EventCatalog.DEFAULT_LOCATION, new DefaultResourceLoader(), state,
                alerts, "test", CLOCK);
        MetricDefinitions metrics = new MetricDefinitions(MetricDefinitions.DEFAULT_LOCATION,
                new DefaultResourceLoader(), state, alerts, "test", CLOCK);
        if (loadCatalog) {
            catalog.afterPropertiesSet();
            metrics.afterPropertiesSet();
        }
        return new CatalogSync(catalog, metrics, state, jdbc, tx, alerts, "test", CLOCK);
    }

    @Test
    void skipsWhenPipelineDisabled() {
        CatalogSync s = sync(true);
        state.disable(Reason.DB_UNAVAILABLE, "x");

        assertThat(s.syncOnStartup()).isFalse();
        verifyNoInteractions(jdbc, tx);
    }

    @Test
    void skipsWhenYamlNotLoaded() {
        CatalogSync s = sync(false);

        assertThat(s.syncOnStartup()).isFalse();
        verifyNoInteractions(jdbc, tx);
    }

    @Test
    void failureAlertsAndDoesNotThrowOrDisable() {
        CatalogSync s = sync(true);
        when(tx.execute(any(TransactionCallback.class))).thenThrow(new DataAccessResourceFailureException("db"));

        assertThat(s.syncOnStartup()).isFalse();
        assertThat(state.isEnabled()).isTrue();
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), startsWith("analytics-catalog-sync-failed:2026-11-13"),
                anyString());
    }

    @Test
    void runsInOneTransactionWhenReady() {
        CatalogSync s = sync(true);
        when(tx.execute(any(TransactionCallback.class))).thenReturn(new int[] {0, 0});

        assertThat(s.syncOnStartup()).isTrue();
        verify(tx, times(1)).execute(any(TransactionCallback.class));
    }

    @Test
    void propertiesJsonShape() {
        CatalogSync s = sync(false);
        String json = s.propertiesJson(List.of(
                new PropertyDefinition("target_user_id", "int", "对方", true),
                new PropertyDefinition("plain", "string", null, false)));

        assertThat(json).isEqualTo("[{\"name\":\"target_user_id\",\"type\":\"int\",\"description\":\"对方\","
                + "\"user_identifier\":true},{\"name\":\"plain\",\"type\":\"string\",\"user_identifier\":false}]");
        assertThat(s.changelogJson(List.of(new ChangelogEntry("2026-11-13", "初版骨架"))))
                .isEqualTo("[{\"date\":\"2026-11-13\",\"change\":\"初版骨架\"}]");
    }

    @Test
    void describeHasNoMessage() {
        String d = CatalogSync.describe(new DataAccessResourceFailureException("jdbc:postgresql://secret-host/db",
                new IllegalStateException("password=xyz")));
        assertThat(d).isEqualTo("DataAccessResourceFailureException <- IllegalStateException");
    }
}
