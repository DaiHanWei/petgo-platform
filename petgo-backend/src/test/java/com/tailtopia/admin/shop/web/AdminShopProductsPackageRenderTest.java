package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.AdminShopProductService;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（V1.4.2 shop-admin Story 4.2 · G5）：商品列表摘要「缺重量体积的 SKU」、商品名旁缺失徽标、翻页带筛选。 */
class AdminShopProductsPackageRenderTest {

    private static Map<String, Object> model() {
        ShopProduct p = Mockito.mock(ShopProduct.class);
        Mockito.when(p.getId()).thenReturn(5L);
        Mockito.when(p.getName()).thenReturn("Royal Canin Kitten");
        Mockito.when(p.getPublicToken()).thenReturn("rc-kitten");
        Mockito.when(p.getBrand()).thenReturn("Royal Canin");
        Mockito.when(p.getCategory()).thenReturn(ProductCategory.values()[0]);
        Mockito.when(p.isActive()).thenReturn(true);
        Map<String, Object> m = new HashMap<>();
        m.put("products", List.of(p));
        m.put("summary", new AdminShopProductService.Summary(38, 6, 71, 2));
        m.put("skuCount", Map.of(5L, 2));
        m.put("minPrice", Map.of(5L, 98_500L));
        m.put("missingPackageByProduct", Map.of(5L, 1L));
        m.put("filterPackage", "MISSING");
        m.put("canEdit", false);
        m.put("page", 0);
        m.put("hasNext", true);
        return m;
    }

    @Test
    void summaryAndBadgeInAllLocales() {
        for (Locale l : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("id"))) {
            String html = AdminShopTemplateRenderSupport.renderInline(
                    "<th:block th:replace=\"~{admin/fragments/shop-products-list :: rows(true)}\"></th:block>", model(), l);
            assertThat(html).doesNotContain("??");
            assertThat(html).contains("data-missing-package").contains("sum-item dg");
            assertThat(html).contains("packageData=MISSING");
        }
    }
}
