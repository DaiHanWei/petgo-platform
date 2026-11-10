package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminShopRefundPayoutService;
import com.tailtopia.admin.shop.service.AdminShopRefundPayoutService.Tab;
import com.tailtopia.pay.refund.domain.PayoutChannel;
import com.tailtopia.shop.order.pricing.RefundQuote;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.PayoutFailReason;
import com.tailtopia.shop.order.refund.RefundScope;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 5.2 · E1 ~ E5）：列表与抽屉片段直渲（三语、账号打码、职责分离置灰、注销用户线下结案）。 */
class AdminShopRefundPayoutRenderTest {

    private static ShopOrderRefund pending(PayoutChannel ch) {
        ShopOrderRefund r = ShopOrderRefund.create("rf-1", 1L, new RefundQuote(RefundScope.WHOLE, null, null,
                CancelReason.OUT_OF_STOCK, 146_500, 0, 0, 146_500, 50_000, 96_500, false), 7L,
                Instant.parse("2026-11-04T06:00:00Z"));
        r.chooseToBank(ch, "1234567890", "RINA WULANDARI", Instant.parse("2026-11-04T06:02:00Z"));
        return r;
    }

    private static Map<String, Object> drawer(ShopOrderRefund r, boolean canPayout, boolean initiator, boolean deleted) {
        Map<String, Object> m = new HashMap<>();
        m.put("refund", r);
        m.put("info", new AdminShopRefundPayoutService.OrderInfo("ord-1", "SO20261104-0042", "Rina W.", deleted));
        m.put("initiatorName", "ops.dina");
        m.put("canPayout", canPayout);
        m.put("isInitiator", initiator);
        m.put("accountShown", canPayout ? r.getPayoutAccount() : "••••" + r.payoutAccountLast4());
        m.put("failReasons", PayoutFailReason.values());
        return m;
    }

    private static String renderDrawer(Map<String, Object> m, Locale l) {
        return AdminShopTemplateRenderSupport.render("admin/fragments/drawer-shop-refund-payout", "panel", m, l);
    }

    @Test
    void drawerInAllLocalesWithPayoutForms() {
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = renderDrawer(drawer(pending(PayoutChannel.BCA), true, false, false), l);
            assertThat(html).doesNotContain("??");
            assertThat(html).contains("1234567890").contains("RINA WULANDARI").contains("Rp 96.500");
            assertThat(html).contains("/paid/confirm").contains("/failed/confirm").doesNotContain("/offline/confirm");
            assertThat(html).contains("data-requires-target");
        }
    }

    @Test
    void readOnlyViewerSeesMaskedAccountAndNoActiveButtons() {
        String html = renderDrawer(drawer(pending(PayoutChannel.OVO), false, false, false), Locale.SIMPLIFIED_CHINESE);
        assertThat(html).doesNotContain("1234567890").contains("••••7890").doesNotContain("data-requires-target")
                .contains("渠道费 Rp 2.500");
    }

    @Test
    void initiatorIsBlocked() {
        String html = renderDrawer(drawer(pending(PayoutChannel.BCA), true, true, false), Locale.SIMPLIFIED_CHINESE);
        assertThat(html).contains("data-duty-blocked").contains("ops.dina").doesNotContain("data-requires-target");
    }

    @Test
    void deletedUserGetsOfflineCloseOnly() {
        String html = renderDrawer(drawer(pending(PayoutChannel.BCA), true, false, true), Locale.SIMPLIFIED_CHINESE);
        assertThat(html).contains("/offline/confirm").doesNotContain("/paid/confirm").doesNotContain("/failed/confirm");
    }

    @Test
    void listBodyMasksAccountAndShowsOverdue() {
        Map<String, Object> m = new HashMap<>();
        m.put("tab", "AWAITING");
        m.put("tabs", Tab.values());
        Map<Tab, Long> counts = new LinkedHashMap<>();
        for (Tab t : Tab.values()) {
            counts.put(t, 1L);
        }
        m.put("counts", counts);
        m.put("summary", new AdminShopRefundPayoutService.Summary(412_000, 1, 249_870, 1));
        m.put("oob", true);
        m.put("rows", List.of(new AdminShopRefundPayoutService.Row("rf-1", "ord-1", "SO20261104-0042", "Rina W.", 96_500,
                "OUT_OF_STOCK", "WHOLE", "GOPAY", "4321", 2_500, "RINA", Instant.parse("2026-11-04T06:02:00Z"),
                "AWAITING_DESTINATION", true, false)));
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = AdminShopTemplateRenderSupport.render("admin/shop-refund-payouts", "listBody", m, l);
            assertThat(html).doesNotContain("??");
            assertThat(html).contains("GOPAY · ••••4321").contains("Rp 96.500").contains("payout-summary");
        }
    }
}
