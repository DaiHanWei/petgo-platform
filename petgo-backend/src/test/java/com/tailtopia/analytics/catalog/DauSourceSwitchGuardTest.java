package com.tailtopia.analytics.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsDauSourceProperties;
import com.tailtopia.analytics.DauSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * L0：日活来源切换日期守门（analytics-pipeline Story 4.7 AC3 / AC9 / T5）——三种组合（GA4、SELF 无日期、SELF 有日期）、
 * 字段解析、告警一次且不抛；真实口径文件 S1 带字段且初始为空。
 */
class DauSourceSwitchGuardTest {

    /** 2026-11-12T20:00Z = WIB 2026-11-13 03:00（去重键按 WIB 日）。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T20:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private final OpsAlertService alerts = mock(OpsAlertService.class);

    private DauSourceSwitchGuard guard(String source, String location) {
        return new DauSourceSwitchGuard(location, new DefaultResourceLoader(), new AnalyticsDauSourceProperties(source),
                alerts, "test", CLOCK);
    }

    private String yamlFile(String s1Extra) throws IOException {
        Path f = dir.resolve("metrics.yaml");
        Files.writeString(f, """
                version: 1
                metrics:
                  - code: S1
                    name: "日活（DAU）"
                    definition: "x"
                %s
                  - code: S2
                    name: "安装量"
                    definition: "y"
                    dau_source_switch: "2026-01-01"
                """.formatted(s1Extra), StandardCharsets.UTF_8);
        return f.toUri().toString();
    }

    // ---------------------------------------------------------------- 纯判断

    @Test
    void onlySelfWithoutDateNeedsWarning() {
        assertThat(DauSourceSwitchGuard.missingSwitchDate(DauSource.GA4, null)).isFalse();
        assertThat(DauSourceSwitchGuard.missingSwitchDate(DauSource.GA4, LocalDate.parse("2026-12-01"))).isFalse();
        assertThat(DauSourceSwitchGuard.missingSwitchDate(DauSource.SELF, null)).isTrue();
        assertThat(DauSourceSwitchGuard.missingSwitchDate(DauSource.SELF, LocalDate.parse("2026-12-01"))).isFalse();
    }

    @Test
    void parsesSwitchDateFromS1Only() throws Exception {
        String head = "version: 1\nmetrics:\n  - code: S1\n    name: n\n    definition: d\n";
        assertThat(DauSourceSwitchGuard.switchDate(head + "    dau_source_switch: null\n")).isNull();
        assertThat(DauSourceSwitchGuard.switchDate(head)).isNull();
        assertThat(DauSourceSwitchGuard.switchDate(head + "    dau_source_switch: \"\"\n")).isNull();
        assertThat(DauSourceSwitchGuard.switchDate(head + "    dau_source_switch: \"2026-12-01\"\n"))
                .isEqualTo(LocalDate.parse("2026-12-01"));
        // 未加引号：SnakeYAML 解析成 Date，也认
        assertThat(DauSourceSwitchGuard.switchDate(head + "    dau_source_switch: 2026-12-01\n"))
                .isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat(DauSourceSwitchGuard.switchDate(head + "    dau_source_switch: \"12/01/2026\"\n")).isNull();
        // 别的条目上的字段不算
        assertThat(DauSourceSwitchGuard.switchDate(
                "version: 1\nmetrics:\n  - code: S2\n    dau_source_switch: \"2026-12-01\"\n")).isNull();
        assertThat(DauSourceSwitchGuard.switchDate((Object) null)).isNull();
        assertThat(DauSourceSwitchGuard.switchDate((Object) Map.of("metrics", List.of("x")))).isNull();
    }

    // ---------------------------------------------------------------- 启动检查（AC9：三种组合）

    @Test
    void ga4NeverAlerts() throws IOException {
        assertThat(guard("GA4", yamlFile("    dau_source_switch: null")).check()).isFalse();
        verify(alerts, never()).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    @Test
    void selfWithoutDateWarnsAndAlertsOnce() throws IOException {
        assertThat(guard("SELF", yamlFile("    dau_source_switch: null")).check()).isTrue();
        verify(alerts, times(1)).send(eq(OpsChannel.TECH),
                eq(DauSourceSwitchGuard.ALERT_KEY_PREFIX + "2026-11-13"), contains("dau_source_switch"));
    }

    @Test
    void selfWithDateDoesNotAlert() throws IOException {
        assertThat(guard("SELF", yamlFile("    dau_source_switch: \"2026-11-20\"")).check()).isFalse();
        verify(alerts, never()).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    /** 口径文件读不到按「缺日期」处理；告警发送失败也不抛（不阻断启动）。 */
    @Test
    void neverThrows() {
        doThrow(new IllegalStateException("boom")).when(alerts).send(eq(OpsChannel.TECH), anyString(), anyString());
        DauSourceSwitchGuard g = guard("self", "classpath:analytics/no-such-file.yaml");
        assertThat(g.check()).isTrue();
        g.onReady();
        verify(alerts, times(2)).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    /** 非法开关值回退 GA4（Story 4.2）→ 不告警（由 Ga4DailySnapshotJob 报配置非法）。 */
    @Test
    void invalidSourceFallsBackToGa4() throws IOException {
        assertThat(guard("GA-4", yamlFile("")).check()).isFalse();
        verify(alerts, never()).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    /** AC3：真实口径文件 S1 已有 dau_source_switch 字段、初始为空；默认 GA4 不告警。 */
    @Test
    void realDefinitionsHaveEmptySwitchField() throws Exception {
        Object root = CatalogYaml.parse(new DefaultResourceLoader().getResource(MetricDefinitions.DEFAULT_LOCATION),
                "metric-definitions.yaml");
        Map<?, ?> s1 = ((List<?>) ((Map<?, ?>) root).get("metrics")).stream().map(m -> (Map<?, ?>) m)
                .filter(m -> "S1".equals(m.get("code"))).findFirst().orElseThrow();
        assertThat(s1.containsKey(DauSourceSwitchGuard.FIELD)).isTrue();
        assertThat(s1.get(DauSourceSwitchGuard.FIELD)).isNull();
        assertThat(guard("GA4", MetricDefinitions.DEFAULT_LOCATION).check()).isFalse();
        assertThat(guard("SELF", MetricDefinitions.DEFAULT_LOCATION).check()).isTrue();
    }
}
