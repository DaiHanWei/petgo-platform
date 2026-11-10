package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminShopOrderService;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 7.1 · F1）：「订单」页摘要五格；「取消后到账待处理」> 0 标红。 */
class AdminShopOrderListRenderTest {

    @Test
    void summaryBarHasFiveCells() {
        String html = AdminShopTemplateRenderSupport.renderInline(
                "<div th:replace=\"~{admin/fragments/shop-order-list-rows :: sumbar(false)}\"></div>",
                Map.of("summary", new AdminShopOrderService.ListSummary(3, 14, 22, 5, 2)), Locale.SIMPLIFIED_CHINESE);
        assertThat(html).contains("待支付").contains("待发货").contains("待收货").contains("退款中").contains("取消后到账待处理");
        assertThat(html.split("class=\"sum-item").length - 1).isEqualTo(5);
        assertThat(html).contains("sum-item dg");
    }

    @Test
    void lateFlagIsSharedWithFulfillmentFilter() {
        assertThat(AdminShopOrderService.LIST_FLAGS).contains("LATE_PAID");
        assertThat(AdminShopOrderService.FLAGS).doesNotContain("LATE_PAID");
        String en = AdminShopTemplateRenderSupport.renderInline("<span th:text=\"#{admin.v142.ful.flag.LATE_PAID}\"></span>",
                Map.of(), Locale.ENGLISH);
        assertThat(en).contains("Paid after cancellation");
    }
}
