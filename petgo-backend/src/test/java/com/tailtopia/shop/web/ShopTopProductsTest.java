package com.tailtopia.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.service.PromoResolver;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.dto.ShopProductSummaryView;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.review.service.ProductReviewQueryService;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shop.service.ShopProductQueryService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（v1.4.2 shop Story 6.1 AC12）：{@code GET /shop/products/top} 钳位与顺序。 */
class ShopTopProductsTest {

    private ShopProductRepository products;
    private ShopProductController controller;

    @BeforeEach
    void setUp() {
        products = Mockito.mock(ShopProductRepository.class);
        ShopSkuRepository skus = Mockito.mock(ShopSkuRepository.class);
        when(skus.findByProductIdInOrderByIdAsc(anyList())).thenReturn(List.of());
        MediaProperties mp = new MediaProperties();
        mp.getOss().setCdnBaseUrl("https://cdn.test");
        ShopProductQueryService query = new ShopProductQueryService(products, skus,
                new InventoryService(Mockito.mock(SkuInventoryRepository.class), 5L), new ShopImageUrlResolver(mp),
                new PromoResolver(Mockito.mock(ShopCouponRepository.class)),
                Mockito.mock(ProductReviewQueryService.class));
        controller = new ShopProductController(query, null, null);
        // 仓储已按 sort_weight DESC, id DESC 给出（与列表同一条查询）
        List<ShopProduct> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ShopProduct p = new ShopProduct() {
            };
            ReflectionTestUtils.setField(p, "id", 100L - i);
            ReflectionTestUtils.setField(p, "publicToken", "tok" + i);
            ReflectionTestUtils.setField(p, "name", "P" + i);
            ReflectionTestUtils.setField(p, "category", ProductCategory.MAKANAN);
            ReflectionTestUtils.setField(p, "active", true);
            rows.add(p);
        }
        when(products.findByActiveTrueOrderBySortWeightDescIdDesc()).thenReturn(rows);
    }

    private List<String> tokens(Integer limit) {
        return controller.top(limit, null).stream().map(ShopProductSummaryView::token).toList();
    }

    @Test
    @DisplayName("缺省 6；0 → 1；负数 → 1；99 → 20（钳位不报错）")
    void limitIsClamped() {
        assertThat(tokens(null)).hasSize(6);
        assertThat(tokens(0)).hasSize(1);
        assertThat(tokens(-3)).hasSize(1);
        assertThat(tokens(99)).hasSize(20);
        assertThat(tokens(8)).hasSize(8);
    }

    @Test
    @DisplayName("顺序按运营权重（仓储序），取前 N；无在售 → []")
    void orderFollowsWeight() {
        assertThat(tokens(3)).containsExactly("tok0", "tok1", "tok2");
        when(products.findByActiveTrueOrderBySortWeightDescIdDesc()).thenReturn(List.of());
        assertThat(controller.top(6, null)).isEmpty();
    }
}
