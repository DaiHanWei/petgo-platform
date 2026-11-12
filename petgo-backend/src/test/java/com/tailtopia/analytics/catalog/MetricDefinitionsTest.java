package com.tailtopia.analytics.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import com.tailtopia.analytics.catalog.MetricDefinitions.MetricDefinition;
import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** L0：口径 yaml 加载 / 校验（analytics-pipeline Story 1.2 AC4、AC5 的纯逻辑部分）。 */
class MetricDefinitionsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T02:00:00Z"), ZoneOffset.UTC);

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final OpsAlertService alerts = mock(OpsAlertService.class);

    private MetricDefinitions load(String location) {
        MetricDefinitions m = new MetricDefinitions(location, new DefaultResourceLoader(), state, alerts, "test", CLOCK);
        m.afterPropertiesSet();
        return m;
    }

    @Test
    void s1HasAllFields() {
        MetricDefinitions m = load(MetricDefinitions.DEFAULT_LOCATION);

        assertThat(m.isLoaded()).isTrue();
        assertThat(state.isEnabled()).isTrue();
        verify(alerts, never()).send(eq(OpsChannel.TECH), anyString(), anyString());
        MetricDefinition s1 = m.metrics().stream().filter(d -> d.code().equals("S1")).findFirst().orElseThrow();
        assertThat(s1.name()).contains("DAU");
        assertThat(s1.definition()).contains("WIB").contains("Application Opened");
        assertThat(s1.sources()).hasSize(2).anySatisfy(s -> assertThat(s).contains("ga4_daily_snapshot"))
                .anySatisfy(s -> assertThat(s).contains("events_detail"));
        assertThat(s1.exclusions()).contains("account_type = 'REAL'");
        assertThat(s1.knownBias()).isNotBlank();
        // Story 4.1：自建口径落地 activity.sql，并注明观察期以 GA4 为准
        assertThat(s1.sqlPath()).isEqualTo("analytics/rollup/activity.sql");
        assertThat(s1.knownBias()).contains("GA4");
        assertThat(s1.changelog()).hasSize(2).first().satisfies(c -> {
            assertThat(c.date()).isEqualTo("2026-11-13");
            assertThat(c.change()).isEqualTo("初版骨架");
        });
    }

    /** Story 3.1 AC3：S5 写明运营位清单、事件名、token 来源表、曝光规则与变更记录。 */
    @Test
    void s5ListsPlacementsEventsAndTokenSources() {
        MetricDefinitions m = load(MetricDefinitions.DEFAULT_LOCATION);

        assertThat(m.isLoaded()).isTrue();
        MetricDefinition s5 = m.metrics().stream().filter(d -> d.code().equals("S5")).findFirst().orElseThrow();
        assertThat(s5.definition()).contains("ops_popup", "toko_banner", "newbie_coupon", "50%", "action = 'register'");
        assertThat(String.join("\n", s5.sources())).contains(
                "ops_popup_shown", "ops_popup_tapped", "ops_popup_closed",
                "toko_banner_shown", "toko_banner_tapped",
                "toko_newbie_coupon_popup_shown", "toko_newbie_coupon_popup_tapped",
                "ops_popups.public_token", "shop_banners.public_token", "shop_coupons.public_token");
        assertThat(s5.exclusions()).contains("account_type = 'REAL'", "growth_backfill_popup_shown", "app_update_prompt_shown");
        assertThat(s5.knownBias()).contains("_reportImpression");
        assertThat(s5.sqlPath()).isNull();
        assertThat(s5.changelog()).singleElement().satisfies(c -> assertThat(c.change()).contains("Story 3.1"));
    }

    /** Story 3.4：电商 #6 / 产品 #1 / 产品 #5 口径条目按代码核对后的实际事件名与变更记录。 */
    @Test
    void story34FunnelAndOnboardingMetrics() {
        MetricDefinitions m = load(MetricDefinitions.DEFAULT_LOCATION);
        assertThat(m.isLoaded()).isTrue();
        java.util.function.Function<String, MetricDefinition> byCode =
                code -> m.metrics().stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();

        MetricDefinition funnel = byCode.apply("电商#6");
        assertThat(funnel.definition()).contains("screen_viewed", "screen = 'toko_page'", "toko_product_shown",
                "toko_product_detail_viewed", "toko_buy_now_tapped", "toko_add_to_cart_tapped", "toko_order_submitted",
                "toko_order_pay_tapped", "toko_order_payment_succeeded", "paid_at IS NOT NULL", "items[].product_token");
        assertThat(String.join("\n", funnel.sources())).contains("shop_orders.paid_at");
        assertThat(funnel.exclusions()).contains("account_type = 'REAL'");
        assertThat(funnel.knownBias()).contains("context.go('/shop')", "autopay");
        assertThat(funnel.changelog()).singleElement().satisfies(c -> assertThat(c.change()).contains("Story 3.4", "按代码修正"));

        MetricDefinition p1 = byCode.apply("产品#1");
        assertThat(String.join("\n", p1.sources())).contains("pet_added", "Application Installed")
                .doesNotContain("pet_profile_create_submitted");
        assertThat(p1.changelog()).singleElement().satisfies(c -> assertThat(c.change()).contains("pet_added", "D-13"));

        MetricDefinition p5 = byCode.apply("产品#5");
        assertThat(p5.definition()).contains("onboarding_completed", "请求成功后", "第 7–13 天");
        assertThat(p5.changelog()).singleElement().satisfies(c -> assertThat(c.change()).contains("Story 3.4", "D-13"));
    }

    @Test
    void duplicateCodeAndMissingNameDisableWithPath() {
        MetricDefinitions m = load("classpath:analytics/bad-metric-definitions.yaml");

        assertThat(m.isLoaded()).isFalse();
        assertThat(m.metrics()).isEmpty();
        assertThat(state.reason()).contains(Reason.CATALOG_INVALID);
        assertThat(state.detail()).hasValueSatisfying(d -> assertThat(d)
                .contains("metrics[1].code 重复：S1")
                .contains("metrics[1].name 不能为空"));
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), eq("analytics-metrics-invalid:2026-11-13"), anyString());
    }

    @Test
    void unquotedDateIsReadAsIsoString() throws CatalogInvalidException {
        String yaml = """
                version: 1
                metrics:
                  - code: X1
                    name: n
                    definition: d
                    changelog:
                      - { date: 2026-11-13, change: c }
                """;
        assertThat(MetricDefinitions.parse(new StringReader(yaml), "inline.yaml").get(0).changelog().get(0).date())
                .isEqualTo("2026-11-13");
    }

    @Test
    void syntaxErrorHasLineNumber() {
        assertThatThrownBy(() -> MetricDefinitions.parse(new StringReader("version: 1\nmetrics:\n  - { code: S1\n"),
                "inline.yaml"))
                .isInstanceOf(CatalogInvalidException.class)
                .hasMessageMatching("(?s).*第 \\d+ 行.*");
    }

    @Test
    void sourcesMustBeStrings() {
        String yaml = """
                version: 1
                metrics:
                  - code: X1
                    name: n
                    definition: d
                    sources: [ok, {a: b}]
                """;
        assertThatThrownBy(() -> MetricDefinitions.parse(new StringReader(yaml), "inline.yaml"))
                .hasMessageContaining("metrics[0].sources[1] 必须是非空字符串");
    }
}
