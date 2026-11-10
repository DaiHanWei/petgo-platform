package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.admin.shop.service.BannerImageRatio;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 3.1）：banner 列表（拖动 / 排期 / 六态 / 品类红字）与抽屉（去权重、券目标、排期、B4、B6）直渲。 */
class AdminShopBannerTemplateRenderTest {

    private static Map<String, Object> row(long id, String state, String type, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("imageKey", "shop-banner/" + id + ".jpg");
        m.put("imageUrl", "https://cdn/" + id);
        m.put("w", 1056);
        m.put("h", 444);
        m.put("active", !"INACTIVE".equals(state));
        m.put("state", state);
        m.put("targetType", type);
        m.put("targetValue", value);
        m.put("publicToken", "bt" + id);
        m.put("startAt", Instant.parse("2026-11-10T01:00:00Z"));
        m.put("endAt", null);
        m.put("couponName", "COUPON".equals(type) ? "Diskon 12.12" : null);
        return m;
    }

    @Test
    void listShowsHandleScheduleSixStatesAndRetiredCategory() {
        Map<String, Object> m = new HashMap<>();
        m.put("banners", List.of(row(1, "LIVE", "COUPON", "c1"), row(2, "OVER_LIMIT", "NONE", null),
                row(3, "TARGET_INVALID", "CATEGORY", "MAKANAN"), row(4, "INACTIVE", "NONE", null)));
        m.put("hasLive", true);
        m.put("canEdit", true);
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = AdminShopTemplateRenderSupport.render("admin/fragments/shop-banners-list", "rows", m, l);
            assertThat(html).doesNotContain("??").contains("data-banner-reorder=\"/admin/shop/banners/reorder\"")
                    .contains("draggable=\"true\"").contains("drag-handle");
        }
        String zh = AdminShopTemplateRenderSupport.render("admin/fragments/shop-banners-list", "rows", m);
        assertThat(zh).contains("生效中").contains("超出展示上限").contains("目标失效").contains("未上架")
                .contains("品类跳转已下线，请改选").contains("Diskon 12.12").contains("2026-11-10 08:00").contains("不限")
                .doesNotContain("权重").doesNotContain("编辑商品");
    }

    private static Map<String, Object> formModel(ShopBannerForm f, Long id, boolean retired, BannerImageRatio.Result ratio) {
        Map<String, Object> m = new HashMap<>();
        m.put("form", f);
        m.put("bannerId", id);
        m.put("existingImage", null);
        m.put("retiredCategory", retired);
        m.put("couponOptions", List.of(Map.of("token", "c1", "name", "Diskon 12.12")));
        m.put("ratio", ratio);
        m.put("formError", null);
        m.put("uploadMaxBytes", 10485760);
        m.put("uploadMaxMb", 10);
        return m;
    }

    @Test
    void drawerHasScheduleCouponTargetNoWeightAndEditTitle() {
        ShopBannerForm f = new ShopBannerForm();
        f.setTargetType("COUPON");
        f.setTargetValue("c1");
        f.setStartLocal(LocalDateTime.of(2026, 11, 10, 8, 0));
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/drawer-shop-banner", "form",
                formModel(f, 9L, false, BannerImageRatio.check(1200, 800)));
        assertThat(html).doesNotContain("??").contains("name=\"startLocal\"").contains("value=\"2026-11-10T08:00\"")
                .contains("name=\"endLocal\"").containsPattern("value=\"c1\"\\s+selected").contains("编辑 Banner")
                .doesNotContain("sortWeight").doesNotContain("value=\"CATEGORY\"").contains("比 App 的 banner 高");
    }

    @Test
    void retiredCategoryBannerMustBeRechosen() {
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/drawer-shop-banner", "form",
                formModel(new ShopBannerForm(), 9L, true, null));
        assertThat(html).contains("App 端点击会落到 Toko 首页").contains("<option value=\"\" selected");
    }
}
