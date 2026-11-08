package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminProductReviewsService.Row;
import com.tailtopia.admin.shop.service.AdminProductReviewsService.Tab;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 3.3）：商品「评价」页签片段直渲（三语、隐藏原因、无删除入口、分页、空态）。 */
class AdminShopProductReviewsRenderTest {

    private static Map<String, Object> model(Tab tab, boolean canEdit) {
        Map<String, Object> m = new HashMap<>();
        m.put("reviewsTab", tab);
        m.put("canEditReviews", canEdit);
        m.put("productId", 5L);
        return m;
    }

    private static Tab tab() {
        Instant t = Instant.parse("2026-08-14T03:00:00Z");
        return new Tab(5L, List.of(
                new Row(11L, 5, "Bagus sekali", 2, "Budi", t, false, null),
                new Row(12L, 1, "promo link", 0, null, t, true, "AD")), 0, 2, 23, 22, 1, 4.6);
    }

    @Test
    void rendersRowsReasonAndActionsInAllLocales() {
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = AdminShopTemplateRenderSupport.render("admin/fragments/shop-product-reviews", "panel",
                    model(tab(), true), l);
            assertThat(html).doesNotContain("??");
            assertThat(html).contains("data-review-id=\"11\"").contains("data-review-id=\"12\"");
            assertThat(html).contains("/admin/shop/products/5/reviews/11/hide/confirm?page=0");
            assertThat(html).contains("/admin/shop/products/5/reviews/12/unhide/confirm?page=0");
            assertThat(html).contains("tab=reviews&amp;page=1");
            assertThat(html).contains("4.6");
            assertThat(html).doesNotContain("delete");
        }
    }

    @Test
    void readOnlyHasNoActions() {
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/shop-product-reviews", "panel",
                model(tab(), false));
        assertThat(html).doesNotContain("hide/confirm").doesNotContain("unhide/confirm");
    }

    @Test
    void emptyState() {
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/shop-product-reviews", "panel",
                model(new Tab(5L, List.of(), 0, 0, 0, 0, 0, null), true));
        assertThat(html).contains("empty").doesNotContain("<table");
    }
}
