package com.tailtopia.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.dto.ShopProductPageResponse;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：C 端商品列表游标分页（Story 4-5 · SHOP-FR-13 / SHOP-NFR-03 / SHOP-NFR-04）。
 *
 * <p>mock 仓储，验游标编解码、页长、hasMore 判定、以及<b>与既有筛选语义的一致性</b>。
 * 既有 {@code ShopProductQueryServiceTest} 一个断言都没改 —— 它守的是全量那条路径。
 */
class ShopProductPagingTest {

    private ShopProductRepository products;
    private ShopProductQueryService service;

    @BeforeEach
    void setUp() {
        products = Mockito.mock(ShopProductRepository.class);
        ShopSkuRepository skus = Mockito.mock(ShopSkuRepository.class);
        SkuInventoryRepository inventoryRepo = Mockito.mock(SkuInventoryRepository.class);
        MediaProperties mediaProps = new MediaProperties();
        mediaProps.getOss().setCdnBaseUrl("https://cdn.test");
        service = new ShopProductQueryService(products, skus,
                new InventoryService(inventoryRepo, 5L), new ShopImageUrlResolver(mediaProps));
        when(skus.findByProductIdInOrderByIdAsc(anyList())).thenReturn(List.of());
    }

    private ShopProduct product(long id, int sortWeight) {
        ShopProduct p = new ShopProduct() {
        };
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "publicToken", "tok" + id);
        ReflectionTestUtils.setField(p, "name", "Royal Canin " + id);
        ReflectionTestUtils.setField(p, "brand", "Royal Canin");
        ReflectionTestUtils.setField(p, "category", ProductCategory.MAKANAN);
        ReflectionTestUtils.setField(p, "sortWeight", sortWeight);
        ReflectionTestUtils.setField(p, "species", Species.DOG);
        ReflectionTestUtils.setField(p, "returnPolicy", ReturnPolicy.NO_RETURN_AFTER_OPEN);
        return p;
    }

    private List<ShopProduct> products(int n) {
        List<ShopProduct> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(product(100 - i, 50 - i));
        }
        return out;
    }

    // ---------- 游标 ----------

    @Test
    @DisplayName("🔴 首页走哨兵游标 —— 与后续页是同一条查询路径，没有「第一页特判」")
    void firstPageUsesTheSentinelCursor() {
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(List.of());

        service.page(null, null, null, null);

        // 哨兵比任何真实行都大 ⇒ keyset 条件对所有行成立 ⇒ 取到的就是第一页。
        verify(products).pageActive(eq(Integer.MAX_VALUE), eq(Long.MAX_VALUE), any());
    }

    @Test
    @DisplayName("游标 round-trip：编码再解码得到同一对 (sortWeight, id)")
    void cursorRoundTrips() {
        var c = new ShopProductCursor(42, 9_000_001L);

        assertThat(ShopProductCursor.decode(c.encode())).isEqualTo(c);
    }

    @Test
    @DisplayName("🔴 游标不是明文 id —— 对外不暴露可推算的顺序信息")
    void cursorIsNotAPlainId() {
        assertThat(new ShopProductCursor(42, 9_000_001L).encode())
                .doesNotContain("9000001")
                .doesNotContain(":");
    }

    @Test
    @DisplayName("空 / 空白游标 = 从头开始（与「空白 q 等同不传」同一条待客之道）")
    void blankCursorMeansStart() {
        assertThat(ShopProductCursor.decode(null)).isEqualTo(ShopProductCursor.START);
        assertThat(ShopProductCursor.decode("")).isEqualTo(ShopProductCursor.START);
        assertThat(ShopProductCursor.decode("   ")).isEqualTo(ShopProductCursor.START);
    }

    @Test
    @DisplayName("🔴 非法游标 → 422，不是 500，也不外泄内部细节")
    void malformedCursorIs422() {
        assertThatThrownBy(() -> ShopProductCursor.decode("not-a-cursor"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("游标无效");
    }

    // ---------- 页长 ----------

    @Test
    @DisplayName("默认页长 20（SHOP-NFR-03），且多取一条用来判断 hasMore")
    void defaultPageSizeIsTwenty() {
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(List.of());

        service.page(null, null, null, null);

        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(products).pageActive(anyInt(), anyLong(), p.capture());
        assertThat(ShopProductQueryService.DEFAULT_PAGE_SIZE).isEqualTo(20);
        assertThat(p.getValue().getPageSize())
                .as("多取一条比再打一次 count 便宜，也不会因两次查询之间有商品上下架而自相矛盾")
                .isEqualTo(21);
    }

    @Test
    @DisplayName("🔴 页长有上限 —— 传个 10000 就等于退化成全量查询，那正是本 story 要消除的")
    void pageSizeIsCapped() {
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(List.of());

        service.page(null, null, null, 10_000);

        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(products).pageActive(anyInt(), anyLong(), p.capture());
        assertThat(p.getValue().getPageSize())
                .isEqualTo(ShopProductQueryService.MAX_PAGE_SIZE + 1);
    }

    @Test
    @DisplayName("非正页长回落默认值，不是抛错也不是 0 条")
    void nonPositiveSizeFallsBackToDefault() {
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(List.of());

        service.page(null, null, null, 0);
        service.page(null, null, null, -5);

        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(products, Mockito.times(2)).pageActive(anyInt(), anyLong(), p.capture());
        assertThat(p.getAllValues()).allSatisfy(x -> assertThat(x.getPageSize()).isEqualTo(21));
    }

    // ---------- hasMore / nextCursor ----------

    @Test
    @DisplayName("🔴 恰好一页：hasMore=false 且 nextCursor 省略（末页不给一个点不动的游标）")
    void exactlyOnePageHasNoMore() {
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(products(20));

        ShopProductPageResponse r = service.page(null, null, null, 20);

        assertThat(r.items()).hasSize(20);
        assertThat(r.hasMore()).isFalse();
        assertThat(r.nextCursor()).isNull();
    }

    @Test
    @DisplayName("🔴 多一条：hasMore=true，items 仍是 20 条，第 21 条**不下发**")
    void probeRowIsNeverEmitted() {
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(products(21));

        ShopProductPageResponse r = service.page(null, null, null, 20);

        assertThat(r.items())
                .as("探测用的那一条只用来判断 hasMore；发出去会让用户看到 21 条并在下一页重复看到它")
                .hasSize(20);
        assertThat(r.hasMore()).isTrue();
        assertThat(r.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("🔴 nextCursor 指向**本页最后一条**，下一页从它之后开始 —— 不重不漏")
    void nextCursorPointsAtTheLastEmittedRow() {
        List<ShopProduct> rows = products(21);
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(rows);

        ShopProductPageResponse r = service.page(null, null, null, 20);

        ShopProduct last = rows.get(19);   // 第 20 条 = 本页最后一条（第 21 条是探测行）
        assertThat(ShopProductCursor.decode(r.nextCursor()))
                .as("指错一条就是下一页重复或丢一件商品")
                .isEqualTo(new ShopProductCursor(last.getSortWeight(), last.getId()));
    }

    @Test
    @DisplayName("🔴 无结果 → 空 items，不是 404（「这个品类暂时没商品」是正常答案）")
    void emptyResultIsAnEmptyPageNotAnError() {
        when(products.pageActiveByCategory(any(), anyInt(), anyLong(), any()))
                .thenReturn(List.of());

        ShopProductPageResponse r = service.page(ProductCategory.MAKANAN, null, null, null);

        assertThat(r.items()).isEmpty();
        assertThat(r.hasMore()).isFalse();
        assertThat(r.nextCursor()).isNull();
    }

    // ---------- 🔴 既有筛选语义在分页路径上逐字不变（AC3） ----------

    @Test
    @DisplayName("🔴 空白 q 与不传 q 走**同一条**查询 —— 搜索框清空必须原样回到列表")
    void blankQueryTakesTheSamePathAsNoQuery() {
        when(products.pageActive(anyInt(), anyLong(), any())).thenReturn(List.of());

        service.page(null, null, null, null);
        service.page(null, "   ", null, null);

        verify(products, Mockito.times(2)).pageActive(anyInt(), anyLong(), any());
        verify(products, never()).pageSearchActive(anyString(), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("🔴 q 与 category 是**与关系**，不是互斥")
    void queryAndCategoryAreAnded() {
        when(products.pageSearchActiveByCategory(anyString(), any(), anyInt(), anyLong(), any()))
                .thenReturn(List.of());

        service.page(ProductCategory.MAKANAN, "royal", null, null);

        // 做成互斥（一搜就清掉品类）会让用户以为筛选没生效。
        verify(products).pageSearchActiveByCategory(eq("%royal%"), eq(ProductCategory.MAKANAN),
                anyInt(), anyLong(), any());
        verify(products, never()).pageSearchActive(anyString(), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("q 走与全量路径**同一个** likePattern（转小写 + 转义 + 加 %%）")
    void queryUsesTheSharedLikePattern() {
        when(products.pageSearchActive(anyString(), anyInt(), anyLong(), any()))
                .thenReturn(List.of());

        service.page(null, "  RoYaL  ", null, null);

        // 分页另起一套 pattern，就会出现「不翻页搜得到、翻页搜不到」。
        verify(products).pageSearchActive(eq("%royal%"), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("只有 category 时走带品类的分页查询，不退化成全量")
    void categoryOnlyUsesTheCategoryQuery() {
        when(products.pageActiveByCategory(any(), anyInt(), anyLong(), any()))
                .thenReturn(List.of());

        service.page(ProductCategory.MAKANAN, null, null, null);

        verify(products).pageActiveByCategory(eq(ProductCategory.MAKANAN), anyInt(), anyLong(),
                any());
        verify(products, never()).pageActive(anyInt(), anyLong(), any());
    }
}
