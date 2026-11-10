package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.dto.AdminCouponForm;
import com.tailtopia.admin.shop.service.AdminShopCouponService;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.domain.ProductCategory;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 2.2）：优惠券列表 / 抽屉 / 详情片段直渲（模板语法、i18n 全覆盖、三态显隐）。 */
class AdminShopCouponTemplateRenderTest {

    private static Map<String, Object> row(String token, String kind, String acquire, String status, Integer total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("name", "Diskon 20rb");
        m.put("nameEn", "Discount 20k");
        m.put("kind", kind);
        m.put("acquire", acquire);
        m.put("amount", 20_000L);
        m.put("minSpend", 100_000L);
        m.put("scopeType", "CATEGORY");
        m.put("scopeValues", List.of("主粮"));
        m.put("issuePeriod", "11-10 – 12-31");
        m.put("validDays", 7);
        m.put("validPeriod", null);
        m.put("issued", 312);
        m.put("total", total);
        m.put("perUser", 1);
        m.put("status", status);
        m.put("editable", !"ENDED".equals(status) && !"STOPPED".equals(status));
        return m;
    }

    private static Map<String, Object> listModel(List<Map<String, Object>> rows) {
        Map<String, Object> m = new HashMap<>();
        m.put("rows", rows);
        m.put("anyCoupon", !rows.isEmpty());
        m.put("canEdit", true);
        m.put("focus", "t1");
        m.put("summary", new AdminShopCouponService.Summary(2, 1, 9, 18_400_000L));
        Map<String, Long> used = new HashMap<>();
        rows.forEach(r -> used.put((String) r.get("token"), 87L));
        m.put("usedByToken", used);
        return m;
    }

    @Test
    void listRendersColumnsSummaryAndActions() {
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = AdminShopTemplateRenderSupport.render("admin/fragments/shop-coupons-list", "rows",
                    listModel(List.of(row("t1", "DISCOUNT", "NEWBIE", "RUNNING", null),
                            row("t2", "FREE_SHIPPING", "CLAIM", "ENDED", 2000))), l);
            assertThat(html).doesNotContain("??");
            assertThat(html).contains("Rp 18.400.000").contains("shop-coupon-row-t1").contains("is-focus")
                    .contains("/admin/shop/coupons/t1/stop/confirm");
        }
        String zh = AdminShopTemplateRenderSupport.render("admin/fragments/shop-coupons-list", "rows",
                listModel(List.of(row("t1", "DISCOUNT", "NEWBIE", "RUNNING", null))));
        assertThat(zh).contains("Rp 20.000 / 满 Rp 100.000").contains("312").contains("不限").contains("指定品类：主粮");
    }

    @Test
    void emptyListShowsTheEmptyState() {
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/shop-coupons-list", "rows", listModel(List.of()));
        assertThat(html).contains("还没有优惠券");
    }

    private static Map<String, Object> formModel(AdminCouponForm f, String token, boolean started) {
        Map<String, Object> m = new HashMap<>();
        m.put("form", f);
        m.put("couponToken", token);
        m.put("started", started);
        m.put("current", token == null ? null : row(token, "DISCOUNT", "CLAIM", started ? "RUNNING" : "NOT_STARTED", 2000));
        m.put("codeMode", f.getCodeMode());
        m.put("formError", null);
        m.put("categories", ProductCategory.values());
        return m;
    }

    @Test
    void newFormShowsAllFieldsAndCodeRowsOnlyForCodeCoupons() {
        AdminCouponForm f = new AdminCouponForm();
        f.setAcquireType(CouponAcquireType.CODE.name());
        f.setIssueStart(LocalDate.of(2026, 11, 10));
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/drawer-shop-coupon", "form",
                formModel(f, null, false));
        assertThat(html).doesNotContain("??").contains("name=\"kind\"").contains("name=\"codeMode\"")
                .contains("name=\"universalCode\"").contains("value=\"2026-11-10\"").contains("hx-post=\"/admin/shop/coupons\"");
    }

    @Test
    void startedCouponOnlyExposesTotalQuantity() {
        AdminCouponForm f = new AdminCouponForm();
        f.setTotalQuantity(2000);
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/drawer-shop-coupon", "form",
                formModel(f, "t9", true));
        assertThat(html).doesNotContain("??").contains("name=\"totalQuantity\"").doesNotContain("name=\"kind\"")
                .doesNotContain("name=\"amount\"").contains("只能增加").contains("/admin/shop/coupons/t9");
    }

    @Test
    void detailShowsFourStatsAndExportForBatch() {
        Map<String, Object> m = new HashMap<>();
        m.put("c", row("t3", "DISCOUNT", "CODE", "RUNNING", 500));
        m.put("stats", new CouponAdminService.CouponStats(146, 3, 52, 1_860_000L));
        m.put("codeMode", "BATCH");
        m.put("universalCode", null);
        m.put("canEdit", true);
        String html = AdminShopTemplateRenderSupport.render("admin/fragments/drawer-shop-coupon", "detail", m);
        assertThat(html).doesNotContain("??").contains("146").contains("52").contains("Rp 1.860.000")
                .contains("/admin/shop/coupons/t3/codes.csv");
        m.put("codeMode", "UNIVERSAL");
        m.put("universalCode", "PAWSI10");
        assertThat(AdminShopTemplateRenderSupport.render("admin/fragments/drawer-shop-coupon", "detail", m))
                .contains("PAWSI10").doesNotContain("codes.csv");
    }
}
