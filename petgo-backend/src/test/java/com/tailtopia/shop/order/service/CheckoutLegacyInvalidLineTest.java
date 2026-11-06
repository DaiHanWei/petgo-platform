package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.support.CheckoutFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * L0：v1.4.2 shop Story 3.1 复审修复 —— 失效行对外 {@code selected} 恒 false（AC5.1），但旧下单与旧预览
 * （S-4 旧契约）仍按<b>库内勾选位</b>判「勾着的失效行」：409 逐行明细 / {@code unavailableLines} 不能失效，
 * 否则旧版 App 结算页看 2 件、提交时被静默只下 1 件、金额变少。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutLegacyInvalidLineTest extends CheckoutPlaceTestBase {

    private static final CartView.CartLine GOOD = new CartView.CartLine("good", "pg", "Produk",
            "3 kg", 100_000L, 1, null, 10L, null, null, null, true, true);
    /** 库内勾着、但已下架：对外 selected=false，selectedInDb=true。 */
    private static final CartView.CartLine DEAD_PICKED = new CartView.CartLine("dead", "pd",
            "Produk", "3 kg", 50_000L, 1, null, 10L, CartView.REASON_DELISTED, null, null, false,
            true);
    private static final CartView.CartLine DEAD_UNPICKED = new CartView.CartLine("dead", "pd",
            "Produk", "3 kg", 50_000L, 1, null, 10L, CartView.REASON_DELISTED, null, null, false,
            false);

    private final ShopSku goodSku = sku(1, "good", 1, 100_000L, true);
    private final ShopSku deadSku = sku(2, "dead", 2, 50_000L, true);

    /**
     * v1.4.2 shop Story 3.2：旧下单改为「LegacyCheckoutAdapter 事务外询价 → place」；有不可买行 / 一件没选时
     * 适配器不带报价 token，判序由 place 的旧模式分支给出（与改动前同文案、同码）。
     */
    @BeforeEach
    void setUp() {
        when(legacy.placeCommand(eq(UID), any())).thenReturn(new PlaceCommand(CheckoutSource.LEGACY,
                new LegacyCheckoutAdapter(null).previewCommand("a1"), null, true, null, null));
    }

    /** 与 CheckoutLineResolver.load 同口径：选中有效行 + 库内勾着的失效行。 */
    private void givenCart(CartView cart) {
        List<ResolvedCheckoutLine> lines = new ArrayList<>();
        for (CartView.CartLine l : cart.selectedLines()) {
            lines.add(line(goodSku, l.qty()));
        }
        for (CartView.CartLine l : cart.invalidLines()) {
            if (l.selectedInDb()) {
                lines.add(line(deadSku, "pd", l.qty(), l.invalidReason(), l.availableStock()));
            }
        }
        givenInputs(inputs(CheckoutPreviewRequest.Source.CART, cart, lines, List.of(),
                new FreeShippingRule(0L, null), true, 0L, addr));
    }

    @Test
    @DisplayName("🔴 勾着的失效行（库内 selected=true）→ 照旧 409 逐行明细，不静默只下有效行")
    void dbSelectedInvalidLineStillBlocks() {
        givenCart(new CartView(List.of(GOOD), List.of(DEAD_PICKED), 100_000L, 100_000L, 1, 1));

        assertThatThrownBy(() -> service.placeOrder(UID, "a1", null, null))
                .isInstanceOf(CheckoutUnavailableException.class)
                .satisfies(e -> assertThat(((CheckoutUnavailableException) e).getLines())
                        .extracting(UnavailableLine::reason)
                        .containsExactly(UnavailableLine.REASON_DELISTED));
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("🔴 整车失效且库内勾着 → 409 逐行明细（不是「请至少选择一件」），与改动前一致")
    void wholeCartInvalidStillReportsLineDetails() {
        givenCart(new CartView(List.of(), List.of(DEAD_PICKED), 0L, 0L, 0, 0));

        assertThatThrownBy(() -> service.placeOrder(UID, "a1", null, null))
                .isInstanceOf(CheckoutUnavailableException.class);
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("整车失效且库内没勾 → 422「请至少选择一件商品」，不落单（既有语义不变）")
    void wholeCartInvalidUnpickedIs422() {
        givenCart(new CartView(List.of(), List.of(DEAD_UNPICKED), 0L, 0L, 0, 0));

        assertThatThrownBy(() -> service.placeOrder(UID, "a1", null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请至少选择一件商品");
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("空车 → 422「购物车为空」（旧文案）")
    void emptyCartIs422() {
        givenCart(new CartView(List.of(), List.of(), 0L, 0L, 0, 0));

        assertThatThrownBy(() -> service.placeOrder(UID, "a1", null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("购物车为空");
    }

    @Test
    @DisplayName("🔴 旧模式：不可售行降级为 INSUFFICIENT_STOCK + available=0（旧版 App 只认两种原因）")
    void legacyNotSellableDowngraded() {
        CartView.CartLine unsellable = new CartView.CartLine("dead", "pd", "Produk", "3 kg",
                50_000L, 1, null, 7L, CartView.REASON_NOT_SELLABLE, null, null, false, true);
        givenCart(new CartView(List.of(GOOD), List.of(unsellable), 100_000L, 100_000L, 1, 1));

        assertThatThrownBy(() -> service.placeOrder(UID, "a1", null, null))
                .isInstanceOf(CheckoutUnavailableException.class)
                .satisfies(e -> assertThat(((CheckoutUnavailableException) e).getLines())
                        .singleElement().satisfies(l -> {
                            assertThat(l.reason()).isEqualTo(UnavailableLine.REASON_INSUFFICIENT_STOCK);
                            assertThat(l.available()).isZero();
                        }));
    }

    @Test
    @DisplayName("🔴 旧视图 unavailableLines 按库内勾选位：勾着的失效行出现，没勾的不出现")
    void legacyViewUnavailableLinesUseDbSelection() {
        CartView cart = new CartView(List.of(GOOD), List.of(DEAD_PICKED), 100_000L, 100_000L, 1, 1);
        var p = new CheckoutService.CheckoutPreview(cart, null, null, null, null, null, null, 0L,
                0L, false, true, Map.of("good", ReturnPolicy.RETURNABLE));
        assertThat(CheckoutPreviewView.of(p).unavailableLines())
                .extracting(CheckoutPreviewView.CheckoutLine::skuToken).containsExactly("dead");

        CartView unpicked = new CartView(List.of(GOOD), List.of(DEAD_UNPICKED), 100_000L, 100_000L, 1, 1);
        var q = new CheckoutService.CheckoutPreview(unpicked, null, null, null, null, null, null, 0L,
                0L, false, true, Map.of("good", ReturnPolicy.RETURNABLE));
        assertThat(CheckoutPreviewView.of(q).unavailableLines()).isEmpty();
    }
}
