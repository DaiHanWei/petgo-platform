package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminShopOrderExceptionService.WholePreview;
import com.tailtopia.admin.shop.service.CancelCategoryMapping;
import com.tailtopia.admin.shop.service.CancelCategoryMapping.Action;
import com.tailtopia.admin.shop.service.CancelCategoryMapping.Category;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.pricing.RefundQuote;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（V1.4.2 shop-admin Story 5.1 · D1 / D4 / D5）：退款明细试算片段直渲（三语、类别差异、按钮可用态）。 */
class AdminShopExceptionBreakdownRenderTest {

    private static Map<String, Object> model(WholePreview pv) {
        ShopOrder order = Mockito.mock(ShopOrder.class);
        Mockito.when(order.getPublicToken()).thenReturn("ord-1");
        Map<String, Object> m = new HashMap<>();
        m.put("order", order);
        m.put("preview", pv);
        return m;
    }

    private static RefundQuote quote(CancelReason r, long total, long coin) {
        return new RefundQuote(RefundScope.WHOLE, null, null, r, total, 0L, 0L, total, coin, total - coin, false);
    }

    private static String render(WholePreview pv, Locale l) {
        return AdminShopTemplateRenderSupport.render("admin/fragments/shop-exception-panel", "breakdown", model(pv), l);
    }

    @Test
    void platformFullRefundInAllLocales() {
        var pv = new WholePreview(new CancelCategoryMapping.Plan(Category.PLATFORM, CancelReason.OUT_OF_STOCK,
                Action.CANCEL_WHOLE), quote(CancelReason.OUT_OF_STOCK, 146_500, 50_000), 146_500, 9_000, 700, 136_800,
                true, 0, false, false);
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = render(pv, l);
            assertThat(html).doesNotContain("??");
            assertThat(html).contains("Rp 50.000").contains("Rp 96.500").contains("Rp 146.500");
            assertThat(html).contains("data-requires-target");
        }
        assertThat(render(pv, Locale.SIMPLIFIED_CHINESE)).contains("（全额）").contains("回补（货还在仓库）");
    }

    @Test
    void userFaultNotInboundDisablesSubmit() {
        var pv = new WholePreview(new CancelCategoryMapping.Plan(Category.USER, CancelReason.USER_FAULT_RETURNED,
                Action.CANCEL_WHOLE), quote(CancelReason.USER_FAULT_RETURNED, 136_800, 46_689), 146_500, 9_000, 700,
                136_800, true, 0, true, false);
        String html = render(pv, Locale.SIMPLIFIED_CHINESE);
        assertThat(html).contains("data-inbound-missing").contains("− Rp 9.000").contains("− Rp 700")
                .contains("不给 · 不退").doesNotContain("data-requires-target");
    }

    @Test
    void uninsuredLostDamagedShowsNegotiatedInputWithoutQuote() {
        var pv = new WholePreview(new CancelCategoryMapping.Plan(Category.LOST_DAMAGED, CancelReason.LOST_DAMAGED,
                Action.CANCEL_WHOLE), null, 146_500, 9_000, 700, 136_800, false, 0, false, true);
        String html = render(pv, Locale.SIMPLIFIED_CHINESE);
        assertThat(html).contains("name=\"negotiatedAmount\"").contains("max=\"136800\"").contains("Rp 136.800")
                .doesNotContain("data-refund-total").doesNotContain("data-requires-target");
    }

    /** 操作区的类别单选（detail 片段含 sec:authorize，整片 L1）：同一表达式内联直渲，挡 SpEL 嵌套类写法错误。 */
    @Test
    void categoryRadiosExpression() {
        Map<String, Object> m = new HashMap<>();
        m.put("categoryAllowed", Map.of("PLATFORM", true, "USER", false, "LOST_DAMAGED", false));
        m.put("selectedCategory", "PLATFORM");
        String html = AdminShopTemplateRenderSupport.renderInline("""
                <label th:each="c : ${T(com.tailtopia.admin.shop.service.CancelCategoryMapping$Category).values()}">
                <input type="radio" name="category" th:value="${c.name()}" th:checked="${selectedCategory == c.name()}"
                       th:disabled="${!categoryAllowed.get(c.name())}"/>
                <b th:text="#{'admin.v142.exc.category.' + ${c.name()}}">x</b>
                <small th:text="#{'admin.v142.exc.category.' + ${c.name()} + '.hint'}">x</small></label>
                """, m, Locale.SIMPLIFIED_CHINESE);
        assertThat(html).doesNotContain("??").contains("平台原因").contains("地址错、联系不上、拒收");
        assertThat(html).contains("value=\"PLATFORM\" checked=\"checked\"");
        assertThat(html).contains("value=\"USER\" disabled=\"disabled\"");
    }
}
