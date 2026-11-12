package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.admin.dailyreport.Ga4ActiveUsersClient;
import com.tailtopia.admin.dailyreport.Ga4ActiveUsersClient.Ga4VersionSplit;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.rollup.Ga4DailySnapshotJob.Outcome;
import com.tailtopia.analytics.rollup.Ga4DailySnapshotJob.RunResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：GA4 快照任务（analytics-pipeline Story 4.2 AC1 / AC2），mock 客户端 / 仓库 / 告警，不出网、不连库。 */
class Ga4DailySnapshotJobTest {

    /** 2026-11-21 01:00 WIB（= 11-20 18:00 UTC）→ WIB 昨天 = 11-20。 */
    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");
    private static final LocalDate Y = LocalDate.of(2026, 11, 20);

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private Ga4ActiveUsersClient client;
    private Ga4SnapshotRepository repo;
    private OpsAlertService alerts;

    @BeforeEach
    void setUp() {
        client = mock(Ga4ActiveUsersClient.class);
        repo = mock(Ga4SnapshotRepository.class);
        alerts = mock(OpsAlertService.class);
        when(client.isConfigured()).thenReturn(true);
        when(repo.upsert(any(), anyLong(), anyLong(), anyString(), any())).thenReturn(true);
    }

    private Ga4DailySnapshotJob job(Ga4SnapshotProperties props) {
        return new Ga4DailySnapshotJob(state, client, repo, props, ReconcileProperties.defaults(), alerts, "test",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Ga4DailySnapshotJob job() {
        return job(Ga4SnapshotProperties.defaults());
    }

    @Test
    void datesAreLastThreeDaysPlusMissingInBackfillWindow() {
        // 补拉窗口内全都有 → 只拉固定的 3 天
        List<LocalDate> full = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            full.add(Y.minusDays(i));
        }
        assertThat(Ga4DailySnapshotJob.datesToFetch(Y, 14, Set.copyOf(full)))
                .containsExactly(Y.minusDays(2), Y.minusDays(1), Y);
        // 缺 Y−5、Y−13 → 补上；窗口外的 Y−14 不管
        Set<LocalDate> missing = new HashSet<>(full);
        missing.remove(Y.minusDays(5));
        missing.remove(Y.minusDays(13));
        assertThat(Ga4DailySnapshotJob.datesToFetch(Y, 14, missing))
                .containsExactly(Y.minusDays(13), Y.minusDays(5), Y.minusDays(2), Y.minusDays(1), Y);
        // 读已有日期失败 → 只拉 3 天
        assertThat(Ga4DailySnapshotJob.datesToFetch(Y, 14, null)).containsExactly(Y.minusDays(2), Y.minusDays(1), Y);
    }

    @Test
    void fetchesAndUpsertsWithMinVersionAndOverwrites() {
        when(repo.existingDates(Y.minusDays(13), Y)).thenReturn(
                Set.of(Y.minusDays(3), Y.minusDays(4), Y.minusDays(5), Y.minusDays(6), Y.minusDays(7), Y.minusDays(8),
                        Y.minusDays(9), Y.minusDays(10), Y.minusDays(11), Y.minusDays(12), Y.minusDays(13), Y,
                        Y.minusDays(1), Y.minusDays(2)));
        when(client.activeUsersByVersion(any(), eq("1.5.0"))).thenReturn(new Ga4VersionSplit(1000, 640));

        RunResult r = job().run();

        assertThat(r.outcome()).isEqualTo(Outcome.DONE);
        assertThat(r.written()).containsExactly(Y.minusDays(2), Y.minusDays(1), Y);
        verify(repo).upsert(eq(Y), eq(1000L), eq(640L), eq("1.5.0"), eq(NOW));
        verify(repo).upsert(eq(Y.minusDays(2)), eq(1000L), eq(640L), eq("1.5.0"), eq(NOW));
        verifyNoInteractions(alerts);
    }

    @Test
    void failedDayAlertsOutsideAndKeepsOldRow() {
        when(repo.existingDates(any(), any())).thenThrow(new IllegalStateException("db"));
        when(client.activeUsersByVersion(any(), anyString())).thenReturn(new Ga4VersionSplit(10, 5));
        when(client.activeUsersByVersion(eq(Y.minusDays(1)), anyString())).thenReturn(null);

        RunResult r = job().run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.failed()).containsExactly(Y.minusDays(1));
        assertThat(r.written()).containsExactly(Y.minusDays(2), Y);
        verify(repo, never()).upsert(eq(Y.minusDays(1)), anyLong(), anyLong(), anyString(), any());
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-ga4:" + Y.minusDays(1)), anyString());
    }

    @Test
    void writeFailureAlertsAndContinues() {
        when(repo.existingDates(any(), any())).thenReturn(Set.of());
        when(client.activeUsersByVersion(any(), anyString())).thenReturn(new Ga4VersionSplit(10, 5));
        when(repo.upsert(eq(Y), anyLong(), anyLong(), anyString(), any())).thenThrow(new IllegalStateException("x"));

        RunResult r = job().run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.failed()).containsExactly(Y);
        assertThat(r.written()).hasSize(13);
        verify(alerts).send(eq(OpsChannel.TECH), eq("analytics-ga4:" + Y), anyString());
    }

    @Test
    void lockHeldElsewhereStops() {
        when(repo.existingDates(any(), any())).thenReturn(Set.of());
        when(client.activeUsersByVersion(any(), anyString())).thenReturn(new Ga4VersionSplit(10, 5));
        when(repo.upsert(any(), anyLong(), anyLong(), anyString(), any())).thenReturn(false);

        RunResult r = job().run();

        assertThat(r.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(r.written()).isEmpty();
        verifyNoInteractions(alerts);
    }

    @Test
    void notConfiguredSkipsWithoutAlertOrFetch() {
        when(client.isConfigured()).thenReturn(false);

        assertThat(job().run().outcome()).isEqualTo(Outcome.SKIPPED_NOT_CONFIGURED);
        verify(client, never()).activeUsersByVersion(any(), anyString());
        verifyNoInteractions(repo, alerts);
    }

    @Test
    void disabledOrPipelineDownSkips() {
        Ga4SnapshotProperties off = new Ga4SnapshotProperties(false, "0 30 2 * * *", 14);
        assertThat(job(off).run().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);

        state.disable(Reason.DB_UNAVAILABLE, "test");
        assertThat(job().run().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        verify(client, never()).activeUsersByVersion(any(), anyString());
        verifyNoInteractions(repo, alerts);
    }

    @Test
    void propertiesValidate() {
        assertThat(Ga4SnapshotProperties.defaults().backfillDays()).isEqualTo(14);
        assertThat(Ga4SnapshotProperties.defaults().invalidReason()).isNull();
        assertThat(new ReconcileProperties(" 1.5.0 ").minAppVersion()).isEqualTo("1.5.0");
        assertThat(new ReconcileProperties(" 1.5.0 ").valid()).isTrue();
    }

    @Test
    void invalidConfigDisablesFeatureInsteadOfFailingStartup() {
        // 代码复审 2026-11-12：埋点配置写错不能让业务后端起不来 —— 构造不抛，只停用快照任务
        Ga4SnapshotProperties badDays = new Ga4SnapshotProperties(true, "-", 2);
        assertThat(badDays.enabled()).isFalse();
        assertThat(badDays.invalidReason()).contains("backfill-days");
        assertThat(badDays.backfillDays()).isEqualTo(14);
        Ga4SnapshotProperties badFlag = new Ga4SnapshotProperties("flase", "0 30 2 * * *", "14");
        assertThat(badFlag.enabled()).isFalse();
        assertThat(badFlag.invalidReason()).contains("enabled");
        assertThat(new Ga4SnapshotProperties(" FALSE ", "-", "14").invalidReason()).isNull();
        assertThat(new Ga4SnapshotProperties("true", "-", "abc").enabled()).isFalse();
        assertThat(new ReconcileProperties("1.5.0-beta").valid()).isFalse();
        assertThat(new ReconcileProperties("1234.1234.1234.1").valid()).isFalse();
        assertThat(new com.tailtopia.analytics.AnalyticsDauSourceProperties("self").dauSource())
                .isEqualTo(com.tailtopia.analytics.DauSource.SELF);
        com.tailtopia.analytics.AnalyticsDauSourceProperties badSource =
                new com.tailtopia.analytics.AnalyticsDauSourceProperties("GA-4");
        assertThat(badSource.valid()).isFalse();
        assertThat(badSource.dauSource()).isEqualTo(com.tailtopia.analytics.DauSource.GA4);

        // 非法配置：run 跳过、不出网不写库；就绪时发一次 TECH 告警
        Ga4DailySnapshotJob bad = new Ga4DailySnapshotJob(state, client, repo, Ga4SnapshotProperties.defaults(),
                new ReconcileProperties("1.5.0-beta"), alerts, "test", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(bad.run().outcome()).isEqualTo(Outcome.SKIPPED_DISABLED);
        verify(client, never()).activeUsersByVersion(any(), anyString());
        verifyNoInteractions(repo);
        bad.checkConfigOnStartup();
        verify(alerts).send(eq(OpsChannel.TECH), eq(Ga4DailySnapshotJob.CONFIG_ALERT_KEY), anyString());

        // 合法配置：就绪检查不告警
        OpsAlertService quiet = mock(OpsAlertService.class);
        new Ga4DailySnapshotJob(state, client, repo, Ga4SnapshotProperties.defaults(), ReconcileProperties.defaults(),
                quiet, "test", Clock.fixed(NOW, ZoneOffset.UTC)).checkConfigOnStartup();
        verifyNoInteractions(quiet);
    }

    @Test
    void springBinderBindsTolerantProperties() {
        // 不起 Spring 上下文，直接用 Boot 的 Binder 验证构造器绑定（含 @DefaultValue 与非法值不抛）
        org.springframework.boot.context.properties.bind.Binder binder =
                new org.springframework.boot.context.properties.bind.Binder(
                        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                                java.util.Map.of("analytics.ga4-snapshot.enabled", "flase",
                                        "analytics.ga4-snapshot.backfill-days", "20",
                                        "analytics.dau-source", "self")));
        Ga4SnapshotProperties snap = binder.bindOrCreate("analytics.ga4-snapshot", Ga4SnapshotProperties.class);
        assertThat(snap.enabled()).isFalse();
        assertThat(snap.invalidReason()).contains("enabled");
        assertThat(snap.backfillDays()).isEqualTo(20);
        assertThat(snap.cron()).isEqualTo("0 30 2 * * *");
        assertThat(binder.bindOrCreate("analytics.reconcile", ReconcileProperties.class).minAppVersion())
                .isEqualTo("1.5.0");
        assertThat(binder.bindOrCreate("analytics", com.tailtopia.analytics.AnalyticsDauSourceProperties.class)
                .dauSource()).isEqualTo(com.tailtopia.analytics.DauSource.SELF);

        org.springframework.boot.context.properties.bind.Binder empty =
                new org.springframework.boot.context.properties.bind.Binder(
                        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource());
        Ga4SnapshotProperties defaults = empty.bindOrCreate("analytics.ga4-snapshot", Ga4SnapshotProperties.class);
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.backfillDays()).isEqualTo(14);
        assertThat(defaults.invalidReason()).isNull();
        assertThat(empty.bindOrCreate("analytics", com.tailtopia.analytics.AnalyticsDauSourceProperties.class)
                .dauSource()).isEqualTo(com.tailtopia.analytics.DauSource.GA4);
    }
}
