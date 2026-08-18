package com.tailtopia.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.dto.ShopProductDetailView;
import com.tailtopia.shop.dto.ShopProductSummaryView;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.domain.StockStatus;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：商品只读查询（Story 1.1 AC4）。mock 仓储，验筛选 / 排序 / 最低价聚合 / 404 分支。
 *
 * <p>与 L1 集成测试互补：此层验业务分支，L1 验真实 HTTP 行为与落库。
 */
class ShopProductQueryServiceTest {

    private ShopProductRepository products;
    private ShopSkuRepository skus;
    private SkuInventoryRepository inventoryRepo;
    private InventoryService inventory;
    private ShopProductQueryService service;

    @BeforeEach
    void setUp() {
        products = Mockito.mock(ShopProductRepository.class);
        skus = Mockito.mock(ShopSkuRepository.class);
        inventoryRepo = Mockito.mock(SkuInventoryRepository.class);
        inventory = new InventoryService(inventoryRepo, 5L);
        // Story 1.6：CDN base 配成固定值，让 mainImageUrl 的派生在本类里可断言
        com.tailtopia.shared.media.MediaProperties mediaProps =
                new com.tailtopia.shared.media.MediaProperties();
        mediaProps.getOss().setCdnBaseUrl("https://cdn.test");
        service = new ShopProductQueryService(products, skus, inventory,
                new ShopImageUrlResolver(mediaProps));
    }

    private ShopProduct product(long id, String token, ProductCategory category) {
        ShopProduct p = new ShopProduct() {
        };
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "publicToken", token);
        ReflectionTestUtils.setField(p, "name", "Royal Canin Medium Adult");
        ReflectionTestUtils.setField(p, "brand", "Royal Canin");
        ReflectionTestUtils.setField(p, "category", category);
        ReflectionTestUtils.setField(p, "mainImageKey", "shop/p/" + id + "/main.jpg");
        ReflectionTestUtils.setField(p, "species", Species.DOG);
        ReflectionTestUtils.setField(p, "detailHtml", "<p>成分表</p>");
        ReflectionTestUtils.setField(p, "shelfLifeNote", "18 bulan");
        ReflectionTestUtils.setField(p, "returnPolicy", ReturnPolicy.NO_RETURN_AFTER_OPEN);
        return p;
    }

    private ShopSku sku(long id, long productId, long price, ReturnPolicy own) {
        ShopSku s = new ShopSku() {
        };
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "publicToken", "sku" + id);
        ReflectionTestUtils.setField(s, "productId", productId);
        ReflectionTestUtils.setField(s, "specName", "3 kg");
        ReflectionTestUtils.setField(s, "price", price);
        ReflectionTestUtils.setField(s, "returnPolicy", own);
        return s;
    }

    @Test
    @DisplayName("列表：category 为 null 时不筛选，走全量已上架查询")
    void listWithoutCategory() {
        when(products.findByActiveTrueOrderBySortWeightDescIdDesc())
                .thenReturn(List.of(product(1L, "tokA", ProductCategory.MAKANAN)));
        when(skus.findByProductIdInOrderByIdAsc(ArgumentMatchers.anyList()))
                .thenReturn(List.of(sku(11L, 1L, 285_000L, null)));

        List<ShopProductSummaryView> out = service.list(null);

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().token()).isEqualTo("tokA");
        Mockito.verify(products).findByActiveTrueOrderBySortWeightDescIdDesc();
        Mockito.verify(products, Mockito.never())
                .findByActiveTrueAndCategoryOrderBySortWeightDescIdDesc(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("列表：category 有值时走筛选查询")
    void listWithCategory() {
        when(products.findByActiveTrueAndCategoryOrderBySortWeightDescIdDesc(ProductCategory.CAMILAN))
                .thenReturn(List.of(product(2L, "tokB", ProductCategory.CAMILAN)));
        when(skus.findByProductIdInOrderByIdAsc(ArgumentMatchers.anyList()))
                .thenReturn(List.of(sku(21L, 2L, 45_000L, null)));

        List<ShopProductSummaryView> out = service.list(ProductCategory.CAMILAN);

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().category()).isEqualTo(ProductCategory.CAMILAN);
        Mockito.verify(products, Mockito.never()).findByActiveTrueOrderBySortWeightDescIdDesc();
    }

    @Test
    @DisplayName("列表：空结果直接短路，不再查 SKU（避免无谓查询）")
    void listEmptyShortCircuits() {
        when(products.findByActiveTrueOrderBySortWeightDescIdDesc()).thenReturn(List.of());

        assertThat(service.list(null)).isEmpty();

        Mockito.verify(skus, Mockito.never()).findByProductIdInOrderByIdAsc(ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("列表：minPrice 取该商品全部 SKU 的最低价")
    void listMinPriceAcrossSkus() {
        when(products.findByActiveTrueOrderBySortWeightDescIdDesc())
                .thenReturn(List.of(product(1L, "tokA", ProductCategory.MAKANAN)));
        when(skus.findByProductIdInOrderByIdAsc(ArgumentMatchers.anyList())).thenReturn(List.of(
                sku(11L, 1L, 285_000L, null),
                sku(12L, 1L, 165_000L, null),
                sku(13L, 1L, 520_000L, null)));

        assertThat(service.list(null).getFirst().minPrice()).isEqualTo(165_000L);
    }

    @Test
    @DisplayName("详情：SKU 级 returnPolicy 为空时继承商品级（FR-94A）")
    void detailInheritsReturnPolicy() {
        ShopProduct p = product(1L, "tokA", ProductCategory.MAKANAN);
        when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.of(p));
        when(skus.findByProductIdOrderByIdAsc(1L)).thenReturn(List.of(
                sku(11L, 1L, 285_000L, null),
                sku(12L, 1L, 165_000L, ReturnPolicy.RETURNABLE)));

        ShopProductDetailView view = service.detail("tokA");

        assertThat(view.skus()).hasSize(2);
        // 继承商品级
        assertThat(view.skus().get(0).returnPolicy()).isEqualTo(ReturnPolicy.NO_RETURN_AFTER_OPEN);
        // SKU 级覆盖
        assertThat(view.skus().get(1).returnPolicy()).isEqualTo(ReturnPolicy.RETURNABLE);
    }

    @Test
    @DisplayName("详情：未知 token → 404（notFound），不是 403 —— 防枚举探测")
    void detailUnknownTokenIsNotFound() {
        when(products.findByPublicTokenAndActiveTrue("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail("nope"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("商品不存在");
    }

    @Test
    @DisplayName("详情：无库存行的 SKU 视为售罄，remaining 为 null（Story 1.2）")
    void detailWithoutInventoryRowIsOutOfStock() {
        ShopProduct p = product(1L, "tokA", ProductCategory.MAKANAN);
        when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.of(p));
        when(skus.findByProductIdOrderByIdAsc(1L)).thenReturn(List.of(sku(11L, 1L, 285_000L, null)));
        when(inventoryRepo.findBySkuIdIn(ArgumentMatchers.anyList())).thenReturn(List.of());

        ShopProductDetailView view = service.detail("tokA");

        assertThat(view.skus().getFirst().stockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(view.skus().getFirst().remaining()).isNull();
    }

    @Test
    @DisplayName("🔒 对外视图不含自增 id —— 只暴露 publicToken（NFR-3）")
    void viewsNeverExposeInternalId() {
        ShopProduct p = product(1L, "tokA", ProductCategory.MAKANAN);
        when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.of(p));
        when(skus.findByProductIdOrderByIdAsc(1L)).thenReturn(List.of(sku(11L, 1L, 285_000L, null)));

        ShopProductDetailView view = service.detail("tokA");

        // record 的组件名即 JSON 字段名：确认没有任何名为 id 的组件
        assertThat(ShopProductDetailView.class.getRecordComponents())
                .noneMatch(c -> c.getName().equals("id"));
        assertThat(view.skus().getFirst().getClass().getRecordComponents())
                .noneMatch(c -> c.getName().equals("id"));
        assertThat(ShopProductSummaryView.class.getRecordComponents())
                .noneMatch(c -> c.getName().equals("id"));
    }
}
