package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminUserCouponsService.Event;
import com.tailtopia.admin.shop.service.AdminUserCouponsService.Row;
import com.tailtopia.admin.shop.service.AdminUserCouponsService.Tab;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 2.3）：用户详情「优惠券」页签直渲（A11 行 + 状态记录、A12 空态、A13 锁定、只读）。 */
class AdminUserCouponsTabRenderTest {

    private static final String PANEL_TRUE = "<div th:replace=\"~{admin/fragments/drawer-users-coupons :: panel(true)}\"></div>";
    private static final String PANEL_FALSE = "<div th:replace=\"~{admin/fragments/drawer-users-coupons :: panel(false)}\"></div>";
    private static final Instant T = Instant.parse("2026-11-05T03:00:00Z");

    private static Row used() {
        return new Row("uct1", "CODE", "USED", "USED", "DISCOUNT", 20_000L, 100_000L, "Diskon", "Discount", T, T.plusSeconds(86400),
                "TOKO-20261105-ABC123", "otok1", List.of(new Event("ISSUED", T, null, null),
                        new Event("LOCKED", T, "TOKO-20261105-ABC123", "otok1"),
                        new Event("USED", T, "TOKO-20261105-ABC123", "otok1")));
    }

    @Test
    void rowsShowNameStatusOrderLinkAndHistoryWithoutAnyWriteEntry() {
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = AdminShopTemplateRenderSupport.renderInline(PANEL_TRUE,
                    Map.of("couponsTab", new Tab(9L, List.of(used()), 27L, false)), l);
            assertThat(html).doesNotContain("??").contains("data-user-coupon=\"uct1\"")
                    .contains("/admin/shop/orders?open=otok1").contains("data-coupon-lock").contains("27");
            assertThat(html).doesNotContain("<form").doesNotContain("hx-post").doesNotContain("uc.id");
        }
        String zh = AdminShopTemplateRenderSupport.renderInline(PANEL_TRUE,
                Map.of("couponsTab", new Tab(9L, List.of(used()), null, false)), Locale.SIMPLIFIED_CHINESE);
        assertThat(zh).contains("满 100.000 减 20.000").contains("兑换获得").contains("被 TOKO-20261105-ABC123 占用")
                .contains("已使用").doesNotContain("data-coupon-lock");
    }

    @Test
    void emptyTabShowsA12() {
        String html = AdminShopTemplateRenderSupport.renderInline(PANEL_FALSE,
                Map.of("couponsTab", new Tab(9L, List.of(), null, false)), Locale.SIMPLIFIED_CHINESE);
        assertThat(html).contains("该用户还没有优惠券").contains("hidden");
    }
}
