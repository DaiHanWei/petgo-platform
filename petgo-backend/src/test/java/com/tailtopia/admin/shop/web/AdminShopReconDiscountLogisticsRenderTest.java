package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.ShopFinanceDashboardService.DiscountLogisticsSnapshot;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 8.1 · I1 / I2）：新整宽卡的行与空态（只在新卡显示空态）。 */
class AdminShopReconDiscountLogisticsRenderTest {

    private static final String MARKUP = "<div th:replace=\"~{admin/fragments/cards-shop-reconciliation :: cards}\"></div>";

    private static Map<String, Object> model(DiscountLogisticsSnapshot dl) {
        var r = new com.tailtopia.admin.shop.service.ShopFinanceDashboardService.ReconciliationSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        return Map.of("r", r, "dl", dl);
    }

    @Test
    void rendersRowsAndNetShipping() {
        var dl = new DiscountLogisticsSnapshot(10_000, 5_000, 3_000, 15_000, 18_000, 12_000, 15_000, 2_000, 1_500,
                4_000, 2, 30_000, 1_000, 2_000, 3_000, 4_000, 50_000, 20_000, 10_000);
        String html = AdminShopTemplateRenderSupport.renderInline(MARKUP, model(dl), Locale.SIMPLIFIED_CHINESE);
        assertThat(html).contains("优惠与物流成本").contains("运费净成本").contains("Rp 15.000")
                .contains("2 单 / Rp 30.000").contains("取消后到账 · 已转新订单").doesNotContain("所选时间段没有数据");
    }

    @Test
    void emptyStateOnlyInNewCard() {
        var dl = new DiscountLogisticsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        String html = AdminShopTemplateRenderSupport.renderInline(MARKUP, model(dl), Locale.SIMPLIFIED_CHINESE);
        assertThat(dl.empty()).as("待索赔是当前口径，不算期间数据").isTrue();
        assertThat(html).contains("所选时间段没有数据").contains("两段之和");
    }
}
