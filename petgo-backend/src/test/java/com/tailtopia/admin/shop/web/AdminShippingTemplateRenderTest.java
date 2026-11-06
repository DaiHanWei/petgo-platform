package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.logistics.balance.BiteshipBalanceEstimate;
import com.tailtopia.shop.logistics.balance.BiteshipBalanceService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 4.1）：页顶横幅 G2 / G3 直渲（三语、条件显隐、金额点分千位）。 */
class AdminShippingTemplateRenderTest {

    private static Map<String, Object> model(boolean originMissing, BiteshipBalanceService.Overview ov) {
        Map<String, Object> m = new HashMap<>();
        m.put("originMissing", originMissing);
        m.put("balance", ov);
        m.put("balanceBelow", ov != null && ov.below());
        return m;
    }

    private static BiteshipBalanceService.Overview low() {
        var r = BiteshipBalanceEstimate.estimate(2_000_000L,
                List.of(new BiteshipBalanceEstimate.Waybill(1_688_000, 0, false)), false);
        return new BiteshipBalanceService.Overview(r, Instant.parse("2026-11-01T03:00:00Z"), "ops.dina", 500_000);
    }

    @Test
    void bannersRenderInAllLocales() {
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = AdminShopTemplateRenderSupport.render("admin/shop-shipping", "shipAlerts", model(true, low()), l);
            assertThat(html).doesNotContain("??");
            assertThat(html).contains("data-ship-origin-missing").contains("data-ship-balance-low");
            assertThat(html).contains("Rp 312.000").contains("Rp 500.000");
        }
    }

    @Test
    void noBannersWhenConfiguredAndAboveThreshold() {
        var r = BiteshipBalanceEstimate.estimate(2_000_000L, List.of(), false);
        var ok = new BiteshipBalanceService.Overview(r, Instant.parse("2026-11-01T03:00:00Z"), null, 500_000);
        String html = AdminShopTemplateRenderSupport.render("admin/shop-shipping", "shipAlerts", model(false, ok));
        assertThat(html).doesNotContain("data-ship-origin-missing").doesNotContain("data-ship-balance-low");
        String none = AdminShopTemplateRenderSupport.render("admin/shop-shipping", "shipAlerts", model(false, null));
        assertThat(none).doesNotContain("banner");
    }

    /** 卡壳含 #authorization（需 web 上下文，L1）；金额明细片段 L0 直渲。 */
    @Test
    void biteshipEstimateRows() {
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String h = AdminShopTemplateRenderSupport.render("admin/shop-shipping", "biteshipEstimate", model(false, low()), l);
            assertThat(h).doesNotContain("??");
        }
        String html = AdminShopTemplateRenderSupport.render("admin/shop-shipping", "biteshipEstimate", model(false, low()));
        assertThat(html).contains("1 单");
        String none = AdminShopTemplateRenderSupport.render("admin/shop-shipping", "biteshipEstimate", model(false, null));
        assertThat(none).contains("data-biteship-not-recorded").doesNotContain("data-biteship-estimate-value");
        assertThat(html).contains("Rp 2.000.000").contains("− Rp 1.688.000").contains("is-danger").contains("ops.dina");
    }
}
