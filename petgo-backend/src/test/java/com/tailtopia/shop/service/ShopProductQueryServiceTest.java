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

    // ---------- 图集（R-2 回归；此前 detail 这一段零覆盖） ----------

    @Test
    @DisplayName("🔴 图集：keys 逐个派生成 CDN URL，顺序与入库一致")
    void detailDerivesGalleryUrlsInOrder() {
        // App 的 _gallery() 是 [mainImageUrl, ...galleryUrls] 直接喂 PageView ——
        // 顺序在这里乱掉，用户左右滑看到的就跟运营在后台排的不是一回事。
        ShopProduct p = product(1L, "tokA", ProductCategory.MAKANAN);
        ReflectionTestUtils.setField(p, "galleryKeys",
                List.of("shop/p/1/g2.jpg", "shop/p/1/g1.jpg"));
        when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.of(p));
        when(skus.findByProductIdOrderByIdAsc(1L))
                .thenReturn(List.of(sku(11L, 1L, 285_000L, null)));

        ShopProductDetailView view = service.detail("tokA");

        assertThat(view.galleryKeys())
                .containsExactly("shop/p/1/g2.jpg", "shop/p/1/g1.jpg");
        assertThat(view.galleryUrls()).containsExactly(
                "https://cdn.test/shop/p/1/g2.jpg", "https://cdn.test/shop/p/1/g1.jpg");
        // 与主图同一套派生规则 —— 两处分别拼前缀迟早会分叉
        assertThat(view.mainImageUrl()).isEqualTo("https://cdn.test/shop/p/1/main.jpg");
    }

    /**
     * 🔴 D-20（2026-09-02 stag，P0）：存量行该列为 NULL，getter 直接把 null 交出去，
     * 调用方一 for 循环就是 NPE —— 后台商品编辑页因此**几乎全部 500**
     * （抽查 8 个商品 7 个挂，只有测试新建的那个能打开），
     * 运营改价 / 改描述 / 换图 / 调权重全部阻断。
     *
     * <p>已在 {@code ShopProduct.getGalleryKeys()} 上收口成空列表。本条从**读取侧**
     * 钉住它：实体字段仍是 null，但任何取用方拿到的都是可安全迭代的空列表。
     */
    @Test
    @DisplayName("🔴 D-20：存量行 galleryKeys 为 null → 取用方拿到空列表，可直接迭代")
    void detailHandlesNullGallery() {
        // 多图是后加的字段，存量商品那一列是 NULL。下发 null 会让 App 端
        // `[mainImageUrl, ...galleryUrls]` 直接炸在展开上；这里必须收敛成空列表。
        ShopProduct p = product(1L, "tokA", ProductCategory.MAKANAN);
        assertThat((Object) ReflectionTestUtils.getField(p, "galleryKeys")).isNull();
        when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.of(p));
        when(skus.findByProductIdOrderByIdAsc(1L))
                .thenReturn(List.of(sku(11L, 1L, 285_000L, null)));

        ShopProductDetailView view = service.detail("tokA");

        assertThat(view.galleryUrls()).isNotNull().isEmpty();
        // 🔴 keys 本身也不能是 null：D-20 的崩点是「拿到 null 就地 for 循环」，
        //    下发 null 只是把同一颗雷交给了客户端。
        assertThat(view.galleryKeys()).isNotNull().isEmpty();
        // 能直接迭代 —— 这正是后台那 7 个 500 挂掉的那一步
        for (String k : view.galleryKeys()) {
            assertThat(k).isNotNull();
        }
    }

    @Test
    @DisplayName("单图商品：图集为空 ⇒ App 天然不可滑、不显示页码")
    void detailSingleImageHasEmptyGallery() {
        // R-2 的现象「看不到滑动效果」正是这一支：模型与端上都就绪，缺的只是数据。
        ShopProduct p = product(1L, "tokA", ProductCategory.MAKANAN);
        ReflectionTestUtils.setField(p, "galleryKeys", List.of());
        when(products.findByPublicTokenAndActiveTrue("tokA")).thenReturn(Optional.of(p));
        when(skus.findByProductIdOrderByIdAsc(1L))
                .thenReturn(List.of(sku(11L, 1L, 285_000L, null)));

        ShopProductDetailView view = service.detail("tokA");

        assertThat(view.galleryUrls()).isEmpty();
        assertThat(view.mainImageUrl()).isNotNull();
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

    // ---------- 关键词搜索（2026-08-31）----------

    @Test
    @DisplayName("空关键词与不传逐字等价——搜索框清空必须原样回到列表")
    void blankQueryFallsBackToPlainList() {
        when(products.findByActiveTrueOrderBySortWeightDescIdDesc())
                .thenReturn(List.of(product(1, "t1", ProductCategory.MAKANAN)));
        when(skus.findByProductIdInOrderByIdAsc(ArgumentMatchers.anyList())).thenReturn(List.of());

        for (String blank : new String[] {null, "", "   "}) {
            assertThat(service.list(null, blank)).hasSize(1);
        }
        // 一次都不该走搜索分支
        Mockito.verify(products, Mockito.never()).searchActive(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("有关键词时走搜索分支，且与品类是与关系")
    void queryDelegatesToSearchAndCombinesWithCategory() {
        when(products.searchActive("%royal%")).thenReturn(List.of());
        when(products.searchActiveByCategory("%royal%", ProductCategory.MAKANAN))
                .thenReturn(List.of());

        assertThat(service.list(null, "Royal")).isEmpty();
        assertThat(service.list(ProductCategory.MAKANAN, "  ROYAL  ")).isEmpty();

        Mockito.verify(products).searchActive("%royal%");
        Mockito.verify(products).searchActiveByCategory("%royal%", ProductCategory.MAKANAN);
        // 走了搜索就绝不该再打普通列表
        Mockito.verify(products, Mockito.never()).findByActiveTrueOrderBySortWeightDescIdDesc();
    }

    /**
     * 🔴 不转义的话，用户敲一个 {@code %} 就等于「匹配全部」——结果里凭空多出商品，
     * 而从输入框上完全看不出为什么。
     */
    @Test
    @DisplayName("LIKE 通配符被转义：% _ \\ 都当字面量搜")
    void likeWildcardsAreEscaped() {
        assertThat(ShopProductQueryService.likePattern("100%")).isEqualTo("%100\\%%");
        assertThat(ShopProductQueryService.likePattern("a_b")).isEqualTo("%a\\_b%");
        // 反斜杠先转，不能把后面刚加的转义符再转一遍
        assertThat(ShopProductQueryService.likePattern("a\\b")).isEqualTo("%a\\\\b%");
        assertThat(ShopProductQueryService.likePattern("  ")).isNull();
        assertThat(ShopProductQueryService.likePattern(null)).isNull();
    }
}
