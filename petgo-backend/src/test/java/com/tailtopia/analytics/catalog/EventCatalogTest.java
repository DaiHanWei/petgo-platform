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
import com.tailtopia.analytics.catalog.EventCatalog.EventDefinition;
import com.tailtopia.analytics.catalog.EventCatalog.PropertyDefinition;
import com.tailtopia.analytics.catalog.EventCatalog.Status;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** L0：事件清单加载 / 校验 / 失败处理（analytics-pipeline Story 1.2 AC1–3、AC5 的纯逻辑部分）。 */
class EventCatalogTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T02:00:00Z"), ZoneOffset.UTC);

    private final AnalyticsPipelineState state = new AnalyticsPipelineState();
    private final OpsAlertService alerts = mock(OpsAlertService.class);

    private EventCatalog load(String location) {
        EventCatalog c = new EventCatalog(location, new DefaultResourceLoader(), state, alerts, "test", CLOCK);
        c.afterPropertiesSet();
        return c;
    }

    private static EventCatalog.Snapshot parse(String yaml) throws CatalogInvalidException {
        return EventCatalog.parse(new StringReader(yaml), "inline.yaml");
    }

    // ---------------------------------------------------------------- 真实清单

    @Test
    void realCatalogLoads() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);

        assertThat(c.isLoaded()).isTrue();
        assertThat(state.isEnabled()).isTrue();
        verify(alerts, never()).send(eq(OpsChannel.TECH), anyString(), anyString());
        assertThat(c.events()).hasSizeGreaterThan(200);
        assertThat(c.isRegistered("toko_add_to_cart_tapped")).isTrue();
        assertThat(c.allowedPropertyKeys("toko_add_to_cart_tapped"))
                .containsExactlyInAnyOrder("product_token", "sku_token", "qty", "unit_price");
        assertThat(c.isRegistered("no_such_event")).isFalse();
        assertThat(c.allowedPropertyKeys("no_such_event")).isEmpty();
        assertThat(c.isRegistered(null)).isFalse();
        assertThat(c.allowedPropertyKeys(null)).isEmpty();
        // YAML 1.1 陷阱：未加引号的 on 会被 SnakeYAML 解析成布尔 true（复审 2026-11-12 #1）
        assertThat(c.allowedPropertyKeys("diary_only_toggled")).containsExactly("on");
    }

    /** Story 1.2 预登记、Epic 2 落地后改 ACTIVE（PLANNED → ACTIVE）。 */
    @Test
    void ac2PlannedEventsPreRegistered() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);
        List<String> planned = List.of("user_identified", "screen_viewed", "Application Installed",
                "Application Updated", "Application Opened");
        Map<String, EventDefinition> byName = new LinkedHashMap<>();
        c.events().forEach(e -> byName.put(e.name(), e));

        for (String name : planned) {
            assertThat(c.isRegistered(name)).as(name).isTrue();
            assertThat(byName.get(name).status()).as(name).isEqualTo(Status.ACTIVE);
        }
        assertThat(c.allowedPropertyKeys("screen_viewed")).contains("screen");
        assertThat(c.allowedPropertyKeys("Application Opened")).contains("from_background");
        assertThat(c.allowedPropertyKeys("push_opened")).contains("push_type", "campaign_token");
        // user_identified 不带明文用户 id
        assertThat(c.allowedPropertyKeys("user_identified")).doesNotContain("internal_user_id", "user_id");
        // PostHog 的 $screen 不登记，由 screen_viewed 取代
        assertThat(c.isRegistered("$screen")).isFalse();
    }

    @Test
    void ac3UserIdentifierKeys() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);

        assertThat(c.userIdentifierKeys()).contains("target_user_id", "owner_id");
        // 非用户标识的常见 id 不应混进来
        assertThat(c.userIdentifierKeys()).doesNotContain("product_token", "pet_id", "place_id", "post_id");
        // 并集与逐条标注一致
        for (EventDefinition e : c.events()) {
            for (PropertyDefinition p : e.properties()) {
                if (p.userIdentifier()) {
                    assertThat(c.userIdentifierKeys()).contains(p.name());
                }
            }
        }
    }

    /** Story 3.3：购物车两个事件落地（PLANNED → ACTIVE），加购补 qty / unit_price；全部不是用户标识（擦除不清）。 */
    @Test
    void cartEventsActiveWithTokensQtyAndUnitPrice() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);
        Map<String, EventDefinition> byName = new LinkedHashMap<>();
        c.events().forEach(e -> byName.put(e.name(), e));

        for (String name : List.of("toko_cart_item_removed", "toko_cart_quantity_changed")) {
            assertThat(byName.get(name).status()).as(name).isEqualTo(Status.ACTIVE);
            assertThat(byName.get(name).codeRef()).as(name)
                    .isEqualTo("petgo_app/lib/features/shop/data/cart_repository.dart");
        }
        assertThat(c.allowedPropertyKeys("toko_cart_item_removed"))
                .containsExactlyInAnyOrder("product_token", "sku_token", "qty", "unit_price", "reason");
        assertThat(c.allowedPropertyKeys("toko_cart_quantity_changed"))
                .containsExactlyInAnyOrder("product_token", "sku_token", "qty_before", "qty_after", "unit_price");
        assertThat(c.userIdentifierKeys())
                .doesNotContain("product_token", "sku_token", "qty", "unit_price", "reason", "qty_before", "qty_after");
    }

    /** Story 3.2：push_opened 落地（PLANNED → ACTIVE），只登记 push_type / campaign_token，都不算用户标识（擦除不清）。 */
    @Test
    void pushOpenedActiveWithTypeAndCampaignToken() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);
        EventDefinition e = c.events().stream().filter(d -> d.name().equals("push_opened")).findFirst().orElseThrow();

        assertThat(e.status()).isEqualTo(Status.ACTIVE);
        assertThat(e.codeRef()).isEqualTo("petgo_app/lib/core/push/push_service.dart");
        assertThat(c.allowedPropertyKeys("push_opened")).containsExactlyInAnyOrder("push_type", "campaign_token");
        assertThat(c.userIdentifierKeys()).doesNotContain("push_type", "campaign_token");
    }

    /** Story 3.4：漏斗第 6 步 toko_order_pay_tapped 登记 items（元素带 product_token）；商品曝光 code_ref 指向 ShopProductCard。 */
    @Test
    void funnelEventsRegisterItemsAndCardCodeRef() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);
        Map<String, EventDefinition> byName = new LinkedHashMap<>();
        c.events().forEach(e -> byName.put(e.name(), e));

        assertThat(c.allowedPropertyKeys("toko_order_pay_tapped")).containsExactly("items");
        assertThat(c.allowedPropertyKeys("toko_order_submitted")).contains("items", "item_count");
        assertThat(byName.get("toko_product_shown").codeRef())
                .isEqualTo("petgo_app/lib/features/shop/presentation/widgets/shop_product_card.dart");
        assertThat(byName.get("onboarding_completed").status()).isEqualTo(Status.ACTIVE);
        assertThat(c.allowedPropertyKeys("pet_added")).containsExactlyInAnyOrder("pet_index", "slot_source");
        assertThat(c.userIdentifierKeys()).doesNotContain("items", "product_token", "sku_id");
    }

    /** Story 3.1：三类运营位事件都登记了 placement_type / placement_token，原属性保留，且不算用户标识（擦除不清）。 */
    @Test
    void placementPropertiesRegisteredForPlacementEvents() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);

        assertThat(c.allowedPropertyKeys("ops_popup_shown")).containsExactlyInAnyOrder("popup_id", "placement_type", "placement_token");
        assertThat(c.allowedPropertyKeys("ops_popup_tapped")).containsExactlyInAnyOrder("popup_id", "placement_type", "placement_token");
        assertThat(c.allowedPropertyKeys("ops_popup_closed")).containsExactlyInAnyOrder("popup_id", "placement_type", "placement_token");
        assertThat(c.allowedPropertyKeys("toko_banner_shown"))
                .containsExactlyInAnyOrder("banner_id", "position", "placement_type", "placement_token");
        assertThat(c.allowedPropertyKeys("toko_banner_tapped"))
                .containsExactlyInAnyOrder("banner_id", "target_type", "position", "placement_type", "placement_token");
        assertThat(c.allowedPropertyKeys("toko_newbie_coupon_popup_shown"))
                .containsExactlyInAnyOrder("coupon_id", "placement_type", "placement_token");
        assertThat(c.allowedPropertyKeys("toko_newbie_coupon_popup_tapped"))
                .containsExactlyInAnyOrder("coupon_id", "action", "placement_type", "placement_token");
        assertThat(c.userIdentifierKeys()).doesNotContain("placement_type", "placement_token");
    }

    @Test
    void realCatalogIsGroupedByDomainAndSortedWithinDomain() {
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);
        List<String> domainsInOrder = new ArrayList<>();
        String prevDomain = null;
        String prevName = null;
        for (EventDefinition e : c.events()) {
            assertThat(e.domain()).as(e.name()).isNotBlank();
            if (!e.domain().equals(prevDomain)) {
                assertThat(domainsInOrder).as("domain %s 应连续成组", e.domain()).doesNotContain(e.domain());
                domainsInOrder.add(e.domain());
                prevDomain = e.domain();
                prevName = null;
            }
            if (prevName != null) {
                assertThat(e.name().compareTo(prevName)).as("%s 应排在 %s 之后", e.name(), prevName).isPositive();
            }
            prevName = e.name();
        }
    }

    /** App 侧 Story 2.6 的比对测试按行解析：每个事件首行必须是 {@code "  - name: "}，属性一律单行行内写法。 */
    @Test
    void realCatalogKeepsLineFormatForAppParser() throws IOException {
        String text;
        try (InputStream in = new DefaultResourceLoader().getResource(EventCatalog.DEFAULT_LOCATION).getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        long nameLines = text.lines().filter(l -> l.startsWith("  - name: ")).count();
        EventCatalog c = load(EventCatalog.DEFAULT_LOCATION);
        assertThat(nameLines).isEqualTo(c.events().size());
        Pattern propLine = Pattern.compile("^ {6}- \\{ name: (\"?)[a-z][a-z0-9_]*\\1, .* }$");
        text.lines().filter(l -> l.startsWith("      - ")).forEach(l -> assertThat(l).matches(propLine));
    }

    // ---------------------------------------------------------------- 坏 yaml

    @Test
    void syntaxErrorDisablesPipelineWithLineNumberAndAlertsOnce() {
        EventCatalog c = load("classpath:analytics/bad-syntax-event-catalog.yaml");

        assertThat(c.isLoaded()).isFalse();
        assertThat(c.events()).isEmpty();
        assertThat(c.isRegistered("fixture_ok_event")).isFalse();
        assertThat(c.userIdentifierKeys()).isEmpty();
        assertThat(state.isEnabled()).isFalse();
        assertThat(state.reason()).contains(Reason.CATALOG_INVALID);
        assertThat(state.detail()).hasValueSatisfying(d -> assertThat(d).containsPattern("第 \\d+ 行"));
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), eq("analytics-catalog-invalid:2026-11-13"), anyString());
    }

    @Test
    void duplicateEventAndPropertyNamesReportPath() {
        EventCatalog c = load("classpath:analytics/duplicate-event-catalog.yaml");

        assertThat(c.isLoaded()).isFalse();
        assertThat(state.reason()).contains(Reason.CATALOG_INVALID);
        assertThat(state.detail()).hasValueSatisfying(d -> assertThat(d)
                .contains("events[1].name 重复：fixture_dup_event")
                .contains("events[1].properties[1].name 重复：place_id"));
        verify(alerts, times(1)).send(eq(OpsChannel.TECH), anyString(), anyString());
    }

    @Test
    void missingFileDisablesWithoutThrowing() {
        EventCatalog c = load("classpath:analytics/no-such-file.yaml");

        assertThat(c.isLoaded()).isFalse();
        assertThat(state.reason()).contains(Reason.CATALOG_INVALID);
        assertThat(state.detail()).hasValueSatisfying(d -> assertThat(d).contains("文件不存在"));
    }

    @Test
    void alertFailureDoesNotThrow() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(alerts).send(eq(OpsChannel.TECH), anyString(), anyString());

        EventCatalog c = load("classpath:analytics/bad-syntax-event-catalog.yaml");

        assertThat(c.isLoaded()).isFalse();
        assertThat(state.reason()).contains(Reason.CATALOG_INVALID);
    }

    @Test
    void validationRulesReportPaths() {
        String yaml = """
                version: 1
                events:
                  - name: ""
                    source: WEB
                    status: LIVE
                    description: "x"
                  - name: ok_event
                    source: APP
                    status: ACTIVE
                    properties:
                      - { name: BadKey, type: string }
                      - { name: good_key, type: varchar }
                      - { name: other_key, type: int, user_identifier: "yes" }
                """;
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(CatalogInvalidException.class)
                .hasMessageContaining("events[0].name 不能为空")
                .hasMessageContaining("events[0].source 取值非法：WEB")
                .hasMessageContaining("events[0].status 取值非法：LIVE")
                .hasMessageContaining("events[1].description 不能为空")
                .hasMessageContaining("events[1].properties[0].name 不符合")
                .hasMessageContaining("events[1].properties[1].type 取值非法")
                .hasMessageContaining("events[1].properties[2].user_identifier 必须是 true / false");
    }

    /** 复审 2026-11-12 #1：未加引号的 on / yes / 数字不能被宽松转成 "true" / "1" 当名字登记。 */
    @Test
    void nonStringNamesRejected() {
        String yaml = """
                version: 1
                events:
                  - name: yes
                    source: APP
                    status: ACTIVE
                    description: x
                    properties:
                      - { name: on, type: bool }
                """;
        assertThatThrownBy(() -> parse(yaml))
                .hasMessageContaining("events[0].name 必须是字符串")
                .hasMessageContaining("events[0].properties[0].name 必须是字符串");
    }

    @Test
    void nameLongerThan128Rejected() {
        String yaml = "version: 1\nevents:\n  - { name: " + "a".repeat(129)
                + ", source: APP, status: ACTIVE, description: x }\n";
        assertThatThrownBy(() -> parse(yaml)).hasMessageContaining("events[0].name 超过 128 个字符");
    }

    @Test
    void wrongVersionOrShapeRejected() {
        assertThatThrownBy(() -> parse("version: 2\nevents: []\n")).hasMessageContaining("version 必须是 1");
        assertThatThrownBy(() -> parse("version: 1\nevents: {}\n")).hasMessageContaining("events 必须是列表");
        assertThatThrownBy(() -> parse("- a\n- b\n")).hasMessageContaining("(根) 必须是映射");
    }

    @Test
    void duplicateYamlKeyIsSyntaxError() {
        String yaml = """
                version: 1
                events:
                  - name: a_event
                    name: b_event
                    source: APP
                    status: ACTIVE
                    description: x
                """;
        assertThatThrownBy(() -> parse(yaml)).hasMessageMatching("(?s).*第 \\d+ 行第 \\d+ 列.*");
    }

    @Test
    void unsafeTagsAreNotInstantiated() {
        String yaml = "version: 1\nevents: !!java.util.ArrayList []\n";
        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(CatalogInvalidException.class);
    }

    @Test
    void userIdentifierDefaultsFalseAndRetiredCountsAsRegistered() throws CatalogInvalidException {
        String yaml = """
                version: 1
                events:
                  - name: old_event
                    source: SERVER
                    status: RETIRED
                    description: "下线了"
                    properties:
                      - { name: target_user_id, type: int, user_identifier: true }
                      - { name: plain_key, type: string }
                """;
        EventCatalog.Snapshot s = parse(yaml);

        assertThat(s.keysByEvent()).containsKey("old_event");
        assertThat(s.events().get(0).properties().get(1).userIdentifier()).isFalse();
        assertThat(s.userIdentifierKeys()).containsExactly("target_user_id");
        assertThat(s.keysByEvent().get("old_event")).containsExactly("target_user_id", "plain_key");
    }
}
