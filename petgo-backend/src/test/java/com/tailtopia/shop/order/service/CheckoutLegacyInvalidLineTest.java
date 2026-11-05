package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.support.CheckoutFixtures.address;
import static com.tailtopia.shop.support.CheckoutFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.merchant.binding.service.MerchantBindingService;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.repository.ShopPawcoinRulesRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import com.tailtopia.shop.shipping.dto.ShippingQuote;
import com.tailtopia.shop.shipping.service.ShippingQuoteService;
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

/**
 * L0：v1.4.2 shop Story 3.1 复审修复 —— 失效行对外 {@code selected} 恒 false（AC5.1），但旧下单与旧预览
 * （S-4 旧契约）仍按<b>库内勾选位</b>判「勾着的失效行」：409 逐行明细 / {@code unavailableLines} 不能失效，
 * 否则旧版 App 结算页看 2 件、提交时被静默只下 1 件、金额变少。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("removal")
class CheckoutLegacyInvalidLineTest {

    private static final long UID = 5L;

    @Mock private CartService carts;
    @Mock private ShippingAddressService addresses;
    @Mock private ShippingQuoteService zoneQuotes;
    @Mock private InventoryService inventory;
    @Mock private ShopSkuRepository skus;
    @Mock private ShopProductRepository products;
    @Mock private ShopOrderRepository orders;
    @Mock private ShopOrderLineRepository orderLines;
    @Mock private ShopPawcoinRulesRepository rules;
    @Mock private PawCoinWalletService wallet;
    @Mock private ShopTokenGenerator tokens;
    @Mock private ShopOrderDisplayNoGenerator displayNos;
    @Mock private IdempotencyService idempotency;
    @Mock private MerchantBindingService bindings;
    @Mock private CheckoutQuoteService quoteChain;
    @Mock private LegacyCheckoutAdapter legacy;

    private CheckoutService service;

    private static final CartView.CartLine GOOD = new CartView.CartLine("good", "pg", "Produk",
            "3 kg", 100_000L, 1, null, 10L, null, null, null, true, true);
    /** 库内勾着、但已下架：对外 selected=false，selectedInDb=true。 */
    private static final CartView.CartLine DEAD_PICKED = new CartView.CartLine("dead", "pd",
            "Produk", "3 kg", 50_000L, 1, null, 10L, CartView.REASON_DELISTED, null, null, false,
            true);
    private static final CartView.CartLine DEAD_UNPICKED = new CartView.CartLine("dead", "pd",
            "Produk", "3 kg", 50_000L, 1, null, 10L, CartView.REASON_DELISTED, null, null, false,
            false);

    @BeforeEach
    void setUp() {
        service = new CheckoutService(carts, addresses, zoneQuotes, inventory, skus, products,
                orders, orderLines, rules, wallet, tokens, displayNos, idempotency, bindings,
                quoteChain, legacy);
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(addresses.require(UID, "a1")).thenReturn(address(UID, "a1", "Kebayoran", "12160"));
        when(zoneQuotes.quote(anyString(), anyLong())).thenReturn(ShippingQuote.of("Kebayoran", 20_000L, false));
        when(skus.findByPublicTokenIn(anyCollection())).thenReturn(List.of(sku(1, "good", 1, 100_000L, true)));
        when(inventory.availableBySkuId(anyList())).thenReturn(Map.of(1L, 10L));
    }

    @Test
    @DisplayName("🔴 勾着的失效行（库内 selected=true）→ 照旧 409 逐行明细，不静默只下有效行")
    void dbSelectedInvalidLineStillBlocks() {
        when(carts.view(UID)).thenReturn(new CartView(List.of(GOOD), List.of(DEAD_PICKED),
                100_000L, 100_000L, 1, 1));

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
        when(carts.view(UID)).thenReturn(new CartView(List.of(), List.of(DEAD_PICKED), 0L, 0L, 0, 0));

        assertThatThrownBy(() -> service.placeOrder(UID, "a1", null, null))
                .isInstanceOf(CheckoutUnavailableException.class);
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("整车失效且库内没勾 → 422「请至少选择一件商品」，不落单（既有语义不变）")
    void wholeCartInvalidUnpickedIs422() {
        when(carts.view(UID)).thenReturn(new CartView(List.of(), List.of(DEAD_UNPICKED), 0L, 0L, 0, 0));

        assertThatThrownBy(() -> service.placeOrder(UID, "a1", null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请至少选择一件商品");
        verify(orders, never()).save(any());
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
