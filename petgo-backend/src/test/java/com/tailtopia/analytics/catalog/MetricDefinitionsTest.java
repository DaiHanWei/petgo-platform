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
        // Story 4.2：观察期取 ga4_daily_snapshot.active_users_all，切换由 Dai 决定（analytics.dau-source）
        assertThat(s1.knownBias()).contains("ga4_daily_snapshot.active_users_all", "analytics.dau-source");
        assertThat(s1.changelog()).hasSize(3).first().satisfies(c -> {
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
        // Story 4.4 填 SQL 位置
        assertThat(s5.changelog()).first().satisfies(c -> assertThat(c.change()).contains("Story 3.1"));
    }

    /** Story 4.4 T6 / T7：S5 运营位指向 placement.sql，写明带单、推广 Toko 判定与已知偏差；S5-推送 写明接收近似与 A13 勘察结论。 */
    @Test
    void story44PlacementAndPushMetrics() throws java.io.IOException {
        MetricDefinitions m = load(MetricDefinitions.DEFAULT_LOCATION);
        assertThat(m.isLoaded()).isTrue();
        java.util.function.Function<String, MetricDefinition> byCode =
                code -> m.metrics().stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();

        MetricDefinition s5 = byCode.apply("S5");
        assertThat(s5.sqlPath()).isEqualTo("analytics/rollup/placement.sql");
        assertThat(s5.definition()).contains("agg_daily_placement", "agg_weekly_placement", "agg_monthly_placement",
                "paid_orders_7d", "点击时间 < paid_at ≤ 点击时间 + 7 天", "paid_at IS NOT NULL", "promotes_toko",
                "target_value = 'TOKO'", "clicks ÷ impressions");
        assertThat(String.join("\n", s5.sources())).contains("shop_orders(user_id, public_token, paid_at)",
                "ops_popups(public_token, target_type, target_value)");
        assertThat(s5.knownBias()).contains("不代表订单一定由该运营位带来", "从未登录的人无法匹配订单", "回补窗口",
                "露出超过 50%").doesNotContain("SQL 由 Story 4.4 填");
        assertThat(s5.changelog()).hasSize(2).last().satisfies(c -> assertThat(c.change()).contains("Story 4.4"));

        MetricDefinition push = byCode.apply("S5-推送");
        assertThat(push.sqlPath()).isEqualTo("analytics/rollup/push.sql");
        assertThat(push.definition()).contains("push_opened", "OPS_ANNOUNCEMENT", "recipient_count", "近似",
                "campaign_opened_persons_cum", "toko_coupon_expiry_push_sent", "COUPON_EXPIRING", "A13",
                "只统计打开数");
        assertThat(push.exclusions()).contains("account_type = 'REAL'", "VET_REPLY");
        assertThat(push.knownBias()).contains("不是推送送达数", "14 天", "只有打开数");
        assertThat(push.changelog()).singleElement().satisfies(c -> assertThat(c.change()).contains("Story 4.4"));

        // AC5：A13 勘察逐类覆盖 ck_notifications_type 当前全集（取当前树里最后一条重建它的迁移）
        String ck;
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource(
                "db/migration/V20261105_1620__rebuild_ck_notifications_type_shop_coupon_reorder.sql").getInputStream()) {
            ck = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("'([A-Z][A-Z0-9_]+)'")
                .matcher(ck.substring(ck.indexOf("ADD CONSTRAINT ck_notifications_type")));
        int n = 0;
        while (t.find()) {
            n++;
            assertThat(push.definition()).as("A13 勘察漏了 %s", t.group(1)).containsPattern("(?<![A-Z0-9_])" + t.group(1) + "(?![A-Z0-9_])");
        }
        assertThat(n).isGreaterThan(40);
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
        assertThat(funnel.changelog()).first().satisfies(c -> assertThat(c.change()).contains("Story 3.4", "按代码修正"));

        MetricDefinition p1 = byCode.apply("产品#1");
        assertThat(String.join("\n", p1.sources())).contains("pet_added", "Application Installed")
                .doesNotContain("pet_profile_create_submitted");
        assertThat(p1.changelog()).first().satisfies(c -> assertThat(c.change()).contains("pet_added", "D-13"));

        MetricDefinition p5 = byCode.apply("产品#5");
        assertThat(p5.definition()).contains("onboarding_completed", "请求成功后", "第 7–13 天");
        assertThat(p5.changelog()).first().satisfies(c -> assertThat(c.change()).contains("Story 3.4", "D-13"));
    }

    /** Story 4.5 T5：电商 #4 / #5 / #6、产品 #2 指向 toko.sql，写明汇总表列、算法与已知偏差。 */
    @Test
    void story45TokoMetrics() {
        MetricDefinitions m = load(MetricDefinitions.DEFAULT_LOCATION);
        assertThat(m.isLoaded()).isTrue();
        java.util.function.Function<String, MetricDefinition> byCode =
                code -> m.metrics().stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();
        for (String code : new String[] {"电商#4", "电商#5", "电商#6", "产品#2"}) {
            MetricDefinition d = byCode.apply(code);
            assertThat(d.sqlPath()).as(code).isEqualTo("analytics/rollup/toko.sql");
            assertThat(d.exclusions()).as(code).contains("account_type = 'REAL'");
            assertThat(d.changelog()).as(code).last().satisfies(c -> assertThat(c.change()).contains("Story 4.5"));
        }
        assertThat(byCode.apply("电商#4").definition()).contains("screen = 'toko_page'", "toko_viewers", "D-12");
        assertThat(String.join("\n", byCode.apply("电商#4").sources())).contains("'/shop' 不计入");
        assertThat(byCode.apply("电商#5").definition()).contains("atc_users", "atc_converted_users", "[t, t + 7 天]",
                "NULLIF(atc_users, 0)");
        MetricDefinition funnel = byCode.apply("电商#6");
        assertThat(funnel.knownBias()).contains("funnel_s7_paid", "[t1, t1 + 7 天]", "跳步即止步",
                "toko_order_payment_succeeded", "匿名人", "D-13");
        assertThat(funnel.changelog()).hasSize(2);
        MetricDefinition p2 = byCode.apply("产品#2");
        assertThat(p2.definition()).contains("orders_submitted", "orders_paid", "orders_created_biz");
        assertThat(p2.knownBias()).contains("覆盖率未达 90%", "orders_paid ÷ orders_created_biz");
    }

    /** Story 4.6 T6：电商 #7 / #8 / #9 指向 cart.sql / product.sql，写明算法、7 天观察窗、四种去向、已知偏差与逐件明细只在明细层 3 个月。 */
    @Test
    void story46CartAndProductMetrics() {
        MetricDefinitions m = load(MetricDefinitions.DEFAULT_LOCATION);
        assertThat(m.isLoaded()).isTrue();
        java.util.function.Function<String, MetricDefinition> byCode =
                code -> m.metrics().stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();
        MetricDefinition cart = byCode.apply("电商#7");
        assertThat(cart.sqlPath()).isEqualTo("analytics/rollup/cart.sql");
        assertThat(cart.definition()).contains("agg_daily_cart", "7 天观察窗", "removed_items", "purchased_items",
                "ordered_unpaid_items", "in_cart_items", "checkout_source = 'CART'", "sku_token", "dwell_p50_minutes",
                "cart_value_idr", "scope = 'PRODUCT'", "atc_users", "D-12");
        assertThat(cart.exclusions()).contains("account_type = 'REAL'", "BUY_NOW");
        // AC3：逐件「哪个用户」只在明细层 3 个月内可查；Story 3.3 的已知偏差
        assertThat(cart.knownBias()).contains("events_detail 保留期（3 个月）", "其他设备", "后台清空", "撤销删除",
                "CART_UNDO", "请求前上报", "qty_after", "非成交价", "只带 sku_token", "is_final");
        assertThat(cart.changelog()).singleElement().satisfies(c -> assertThat(c.change()).contains("Story 4.6"));
        for (String code : new String[] {"电商#8", "电商#9"}) {
            MetricDefinition d = byCode.apply(code);
            assertThat(d.sqlPath()).as(code).isEqualTo("analytics/rollup/product.sql");
            assertThat(d.definition()).as(code).contains("agg_daily_product", "不预排名");
            assertThat(d.changelog()).as(code).singleElement()
                    .satisfies(c -> assertThat(c.change()).contains("Story 4.6"));
        }
        assertThat(byCode.apply("电商#8").definition()).contains("detail_views + add_to_cart", "LIMIT 3");
        MetricDefinition soldOut = byCode.apply("电商#9");
        assertThat(soldOut.definition()).contains("is_sold_out", "paid_qty_30d", "actual − locked", "D-13");
        assertThat(soldOut.knownBias()).contains("快照", "回补重算不覆盖", "不扣退款");
    }

    /** Story 4.3 AC5：市场 #1–#3、产品 #1、产品 #5 指向汇总 SQL，写明按周加权的现算 SQL、已知偏差与变更记录。 */
    @Test
    void story43RetentionAndInstallProfileMetrics() {
        MetricDefinitions m = load(MetricDefinitions.DEFAULT_LOCATION);
        assertThat(m.isLoaded()).isTrue();
        java.util.function.Function<String, MetricDefinition> byCode =
                code -> m.metrics().stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();

        String[][] market = {{"市场#1", "d1_retained"}, {"市场#2", "d3_retained"}, {"市场#3", "d7_retained"}};
        for (String[] mk : market) {
            MetricDefinition d = byCode.apply(mk[0]);
            assertThat(d.sqlPath()).isEqualTo("analytics/rollup/retention.sql");
            assertThat(d.definition()).contains("Application Installed", "Application Opened", "WIB",
                    "sum(" + mk[1] + ")::numeric / NULLIF(sum(cohort_size), 0)", "cohort_type = 'INSTALL'",
                    mk[1] + " IS NOT NULL");
            assertThat(d.exclusions()).contains("account_type = 'REAL'");
            assertThat(d.knownBias()).contains("卸载重装", "保留期", "晚到超过 3 天不回算", "V1.5.0");
            assertThat(d.changelog()).singleElement().satisfies(c -> assertThat(c.change()).contains("Story 4.3"));
        }

        MetricDefinition p1 = byCode.apply("产品#1");
        assertThat(p1.sqlPath()).isEqualTo("analytics/rollup/install_profile.sql");
        assertThat(p1.knownBias()).contains("agg_monthly_install_profile", "with_pet_profile", "当月新装",
                "次月建档不计入");
        assertThat(p1.changelog()).hasSize(2).last().satisfies(c -> assertThat(c.change()).contains("Story 4.3"));

        MetricDefinition p5 = byCode.apply("产品#5");
        assertThat(p5.sqlPath()).isEqualTo("analytics/rollup/retention.sql");
        assertThat(p5.knownBias()).contains("cohort_type = 'ONBOARDING'",
                "sum(w1_retained)::numeric / NULLIF(sum(cohort_size), 0)", "w1_retained IS NOT NULL", "生效日期");
        assertThat(p5.changelog()).hasSize(2).last().satisfies(c -> assertThat(c.change()).contains("Story 4.3"));
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
