package com.tailtopia.shop.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.cart.domain.ShopCart;
import com.tailtopia.shop.cart.domain.ShopCartItem;
import com.tailtopia.shop.cart.repository.ShopCartItemRepository;
import com.tailtopia.shop.cart.repository.ShopCartRepository;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shop.support.CheckoutFixtures;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：失效行不可勾选 / 全选只勾有效行（v1.4.2 shop Story 3.1 AC5）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceSelectionTest {

    private static final long CART_ID = 77L;
    private static final long USER = 9_000_002L;

    @Mock private ShopCartRepository carts;
    @Mock private ShopCartItemRepository items;
    @Mock private ShopSkuRepository skus;
    @Mock private ShopProductRepository products;
    @Mock private InventoryService inventory;
    @Mock private ShopImageUrlResolver imageUrls;

    private CartService service;
    private final List<ShopSku> skuRows = new ArrayList<>();
    private final List<ShopProduct> productRows = new ArrayList<>();
    private final Map<Long, Long> available = new HashMap<>();
    private final List<ShopCartItem> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CartService(carts, items, skus, products, inventory, imageUrls);
        ShopCart cart = ShopCart.forUser(USER);
        ReflectionTestUtils.setField(cart, "id", CART_ID);
        when(carts.findByUserId(USER)).thenReturn(Optional.of(cart));
        when(skus.findAllById(anyList())).thenAnswer(inv -> skuRows);
        when(skus.findAll()).thenAnswer(inv -> skuRows);
        when(skus.findByPublicTokenIn(anyCollection())).thenAnswer(inv -> {
            Collection<?> t = inv.getArgument(0);
            return skuRows.stream().filter(s -> t.contains(s.getPublicToken())).toList();
        });
        when(products.findAllById(anyList())).thenAnswer(inv -> productRows);
        when(inventory.availableBySkuId(anyList())).thenAnswer(inv -> available);
        when(items.findByCartIdOrderByIdAsc(anyLong())).thenAnswer(inv -> rows);
        when(items.updateSelected(anyLong(), anyLong(), anyBoolean())).thenReturn(1);

        seed(1L, true, 10L, true);    // 有效
        seed(2L, false, 10L, true);   // 下架
        seed(3L, true, 0L, true);     // 售罄
    }

    private void seed(long id, boolean active, long stock, boolean selected) {
        productRows.add(CheckoutFixtures.product(100 + id, "p" + id, active,
                com.tailtopia.shop.domain.ReturnPolicy.RETURNABLE));
        skuRows.add(CheckoutFixtures.sku(id, "s" + id, 100 + id, 10_000L, true));
        available.put(id, stock);
        ShopCartItem item = ShopCartItem.of(CART_ID, id, 1);
        item.setSelected(selected);
        rows.add(item);
    }

    @Test
    @DisplayName("勾选失效行 → 422「该商品当前不可购买」，不写库")
    void selectingInvalidLineIs422() {
        for (String token : List.of("s2", "s3")) {
            assertThatThrownBy(() -> service.setSelected(USER, token, true))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
        }
        verify(items, never()).updateSelected(anyLong(), anyLong(), eq(true));
    }

    @Test
    @DisplayName("取消勾选失效行照常成功；勾选有效行照常成功")
    void deselectingInvalidAndSelectingValidSucceed() {
        service.setSelected(USER, "s2", false);
        verify(items).updateSelected(CART_ID, 2L, false);
        service.setSelected(USER, "s1", true);
        verify(items).updateSelected(CART_ID, 1L, true);
    }

    @Test
    @DisplayName("全选只更新有效 skuId；全不选作用于全部行")
    @SuppressWarnings("unchecked")
    void selectAllOnlyTouchesValidLines() {
        service.setAllSelected(USER, true);
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(items).updateSelectedForSkuIds(eq(CART_ID), ids.capture(), eq(true));
        assertThat(ids.getValue()).containsExactly(1L);
        verify(items, never()).updateAllSelected(anyLong(), eq(true));

        service.setAllSelected(USER, false);
        verify(items).updateAllSelected(CART_ID, false);
    }

    @Test
    @DisplayName("view：失效行下发 selected=false（库内为 true，不回写）")
    void viewReportsInvalidLinesUnselected() {
        var v = service.view(USER);
        assertThat(v.invalidLines()).extracting(l -> l.selected()).containsOnly(false);
        assertThat(v.lines()).singleElement().extracting(l -> l.selected()).isEqualTo(true);
        verify(items, never()).save(any());
    }

    @Test
    @DisplayName("复审修复：view 失效行保留库内勾选位 selectedInDb=true（供旧下单 / 旧预览判定），对外 selected=false")
    void viewKeepsDbSelectionForInvalidLines() {
        var v = service.view(USER);
        assertThat(v.invalidLines()).allSatisfy(l -> {
            assertThat(l.selected()).isFalse();
            assertThat(l.selectedInDb()).isTrue();
        });
    }

    @Test
    @DisplayName("复审修复：viewReadOnly 无购物车 → 空车，不 INSERT shop_carts")
    void viewReadOnlyWithoutCartDoesNotInsert() {
        when(carts.findByUserId(123L)).thenReturn(Optional.empty());
        var v = service.viewReadOnly(123L);
        assertThat(v.lines()).isEmpty();
        assertThat(v.invalidLines()).isEmpty();
        verify(carts, never()).save(any());
    }

    @Test
    @DisplayName("viewReadOnly 有购物车时与 view 同一结果")
    void viewReadOnlyMatchesView() {
        assertThat(service.viewReadOnly(USER)).isEqualTo(service.view(USER));
    }
}
