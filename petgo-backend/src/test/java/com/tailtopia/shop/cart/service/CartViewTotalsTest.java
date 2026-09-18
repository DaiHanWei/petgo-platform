package com.tailtopia.shop.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.cart.domain.ShopCart;
import com.tailtopia.shop.cart.domain.ShopCartItem;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.repository.ShopCartItemRepository;
import com.tailtopia.shop.cart.repository.ShopCartRepository;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：{@code CartService.view} 的两套合计口径（Story 4-1 AC3）。
 *
 * <p>🎯 <b>变异靶子</b>：本类存在的唯一理由，是让「有人把 {@code subtotal} 顺手改成条件累加」
 * 这件事在 <b>L0</b> 就变红。这个护栏本来只有 L1（{@code CartIntegrationTest}）能测 ——
 * 而 L1 在云端跑不了，等于合并前没人能验证它。
 *
 * <p>为什么这条不能漂：线上老版本 App 的购物车底栏读的就是 {@code subtotal}，
 * 而那个界面上<b>没有勾选框可点</b>。把它改成选中合计，老用户会看到一个自己
 * 既解释不了也纠正不了的金额（AD-S6 写「{@code subtotal} 语义不变」的全部理由）。
 *
 * <p>用 mock 而不是真库：这里要钉的是<b>累加逻辑</b>，与持久化无关。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartViewTotalsTest {

    @Mock
    private ShopCartRepository carts;
    @Mock
    private ShopCartItemRepository items;
    @Mock
    private ShopSkuRepository skus;
    @Mock
    private ShopProductRepository products;
    @Mock
    private InventoryService inventory;
    @Mock
    private ShopImageUrlResolver imageUrls;

    private CartService service;

    private static final long CART_ID = 77L;
    private static final long USER = 9_000_001L;

    @BeforeEach
    void setUp() {
        service = new CartService(carts, items, skus, products, inventory, imageUrls);
        ShopCart cart = ShopCart.forUser(USER);
        ReflectionTestUtils.setField(cart, "id", CART_ID);
        when(carts.findByUserId(USER)).thenReturn(Optional.of(cart));
        when(imageUrls.publicUrl(any())).thenReturn("https://cdn.petgo/p/a.jpg");
        when(skus.findAllById(anyList())).thenAnswer(inv -> stubbedSkus);
        when(products.findAllById(anyList())).thenAnswer(inv -> stubbedProducts);
        when(inventory.availableBySkuId(anyList())).thenAnswer(inv -> availability);
    }

    /** 造一个已上架商品 + 一个 SKU + 一条车行（走真实工厂，不反射造实体）。 */
    private ShopCartItem row(long skuId, long price, int qty, boolean selected, long available) {
        long productId = 100L + skuId;
        ShopSku sku = ShopSku.create("sku-" + skuId, productId, "3 kg", price, null,
                com.tailtopia.shop.domain.ReturnPolicy.RETURNABLE);
        ReflectionTestUtils.setField(sku, "id", skuId);

        ShopProduct p = ShopProduct.create("prd-" + skuId, "Royal Canin", "RC",
                com.tailtopia.shop.domain.ProductCategory.MAKANAN, "key", null, null, List.of(),
                com.tailtopia.shop.domain.Species.DOG, null, null, "<p/>", List.of(), "n",
                com.tailtopia.shop.domain.ReturnPolicy.RETURNABLE, 0);
        ReflectionTestUtils.setField(p, "id", productId);
        ReflectionTestUtils.setField(p, "active", true);

        stubbedSkus.add(sku);
        stubbedProducts.add(p);
        availability.put(skuId, Math.max(available, 0L));

        ShopCartItem item = ShopCartItem.of(CART_ID, skuId, qty);
        item.setSelected(selected);
        return item;
    }

    private final List<ShopSku> stubbedSkus = new java.util.ArrayList<>();
    private final List<ShopProduct> stubbedProducts = new java.util.ArrayList<>();
    private final Map<Long, Long> availability = new java.util.HashMap<>();

    private void withRows(ShopCartItem... rows) {
        when(items.findByCartIdOrderByIdAsc(anyLong())).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("🎯 两行有效、只选一行：subtotal 是**两行**合计，selectedSubtotal 才是一行")
    void subtotalCountsEveryValidLineWhileSelectedSubtotalCountsOnlyPicked() {
        withRows(row(1L, 100_000L, 2, true, 10L),      // 选中：200.000，2 件
                row(2L, 50_000L, 3, false, 10L));      // 没选：150.000，3 件

        CartView v = service.view(USER);

        assertThat(v.subtotal())
                .as("🎯 把 subtotal 改成条件累加，这条必须红 —— 老版本 App 的底栏读的就是它")
                .isEqualTo(350_000L);
        assertThat(v.itemCount())
                .as("🎯 itemCount 同理：件数口径，与勾选无关")
                .isEqualTo(5);
        assertThat(v.selectedSubtotal()).isEqualTo(200_000L);
        assertThat(v.selectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("全不选：selectedSubtotal / selectedCount 归零，subtotal / itemCount 不变")
    void deselectingEverythingLeavesSubtotalIntact() {
        withRows(row(1L, 100_000L, 2, false, 10L),
                row(2L, 50_000L, 3, false, 10L));

        CartView v = service.view(USER);

        assertThat(v.subtotal()).isEqualTo(350_000L);
        assertThat(v.itemCount()).isEqualTo(5);
        assertThat(v.selectedSubtotal()).isZero();
        assertThat(v.selectedCount()).isZero();
    }

    @Test
    @DisplayName("🔴 售罄行即使勾着也不计入两套合计中的任何一套")
    void soldOutLineCountsInNeitherTotal() {
        withRows(row(1L, 100_000L, 2, true, 10L),
                row(2L, 50_000L, 3, true, 0L));   // 勾着，但售罄

        CartView v = service.view(USER);

        assertThat(v.invalidLines()).hasSize(1);
        assertThat(v.invalidLines().getFirst().selected())
                .as("失效行的 selected 照实下发，不被改写成 false")
                .isTrue();
        assertThat(v.subtotal()).as("subtotal 本来就只含有效行（文档的「全车合计」是笔误）")
                .isEqualTo(200_000L);
        assertThat(v.selectedSubtotal())
                .as("过滤条件必须是「selected && invalidReason == null」")
                .isEqualTo(200_000L);
        assertThat(v.selectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("全选中（= 老版本行为）：两套合计恒等")
    void legacyAllSelectedMakesBothTotalsIdentical() {
        withRows(row(1L, 100_000L, 2, true, 10L),
                row(2L, 50_000L, 3, true, 10L));

        CartView v = service.view(USER);

        assertThat(v.selectedSubtotal())
                .as("老版本 App 从不调选择端点 ⇒ 每行都选中 ⇒ 两个数必须一模一样")
                .isEqualTo(v.subtotal());
        assertThat(v.selectedCount()).isEqualTo(v.itemCount());
    }

    @Test
    @DisplayName("空车：四个合计全零，不抛")
    void emptyCartHasZeroEverything() {
        when(items.findByCartIdOrderByIdAsc(anyLong())).thenReturn(List.of());

        CartView v = service.view(USER);

        assertThat(v.subtotal()).isZero();
        assertThat(v.selectedSubtotal()).isZero();
        assertThat(v.itemCount()).isZero();
        assertThat(v.selectedCount()).isZero();
    }
}
