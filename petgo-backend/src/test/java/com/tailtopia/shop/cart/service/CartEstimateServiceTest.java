package com.tailtopia.shop.cart.service;

import static com.tailtopia.shop.support.CheckoutFixtures.NOW;
import static com.tailtopia.shop.support.CheckoutFixtures.address;
import static com.tailtopia.shop.support.CheckoutFixtures.cartLine;
import static com.tailtopia.shop.support.CheckoutFixtures.product;
import static com.tailtopia.shop.support.CheckoutFixtures.quote;
import static com.tailtopia.shop.support.CheckoutFixtures.rate;
import static com.tailtopia.shop.support.CheckoutFixtures.settings;
import static com.tailtopia.shop.support.CheckoutFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.dto.CartEstimate;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.coupon.CouponFixtures;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.service.CouponQueryService;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.logistics.Serviceability;
import com.tailtopia.shop.logistics.ShippingQuoteService;
import com.tailtopia.shop.order.repository.ShopPawcoinRulesRepository;
import com.tailtopia.shop.order.service.CheckoutLineResolver;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** L0：购物车底栏预估（v1.4.2 shop Story 3.1 AC3）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartEstimateServiceTest {

    private static final long UID = 9L;

    @Mock private CartService carts;
    @Mock private ShippingAddressService addresses;
    @Mock private ShippingAddressRepository addressRepo;
    @Mock private ShopSkuRepository skus;
    @Mock private ShopProductRepository products;
    @Mock private InventoryService inventory;
    @Mock private ShopImageUrlResolver imageUrls;
    @Mock private CouponQueryService coupons;
    @Mock private ShippingSettingsRepository settingsRepo;
    @Mock private ShopPawcoinRulesRepository coinRulesRepo;
    @Mock private PawCoinWalletService wallet;
    @Mock private ShippingQuoteService quotes;

    private CartEstimateService service;

    @BeforeEach
    void setUp() {
        CheckoutLineResolver resolver = new CheckoutLineResolver(carts, addresses, addressRepo,
                skus, products, inventory, imageUrls, coupons, settingsRepo, coinRulesRepo, wallet);
        service = new CartEstimateService(resolver, quotes, Clock.fixed(NOW, ZoneOffset.UTC));
        when(skus.findByPublicTokenIn(anyCollection()))
                .thenReturn(List.of(sku(1, "s1", 1, 100_000L, true)));
        when(products.findAllById(any()))
                .thenReturn(List.of(product(1, "ps1", true, ReturnPolicy.RETURNABLE)));
        when(settingsRepo.findById(ShippingSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings(300_000L, null, 50)));
        when(coupons.checkoutCandidates(eq(UID), any())).thenReturn(List.of());
    }

    private static CartView cart(boolean selected) {
        return new CartView(List.of(cartLine("s1", "ps1", 100_000L, 2, 10, selected)), List.of(),
                200_000L, selected ? 200_000L : 0L, 2, selected ? 2 : 0);
    }

    @Test
    @DisplayName("一件没选 → NO_SELECTION、金额全 0、不询价")
    void noSelection() {
        CartEstimate e = service.estimate(UID, cart(false), 5L);
        assertThat(e.reason()).isEqualTo(CartEstimate.NO_SELECTION);
        assertThat(e.payable()).isZero();
        assertThat(e.goodsAmount()).isZero();
        assertThat(e.lowestFee()).isNull();
        verifyNoInteractions(quotes);
    }

    @Test
    @DisplayName("默认地址优先于 regionKecamatanId")
    void defaultAddressWins() {
        when(addressRepo.findByUserIdAndIsDefaultTrue(UID))
                .thenReturn(Optional.of(address(UID, "a1", "Kebayoran", "12160")));
        when(quotes.checkAddress(eq("Kebayoran"), eq("12160"), anyList(), eq(UID)))
                .thenReturn(Serviceability.of(quote("q", rate("jne", "reg", 10_000L, true),
                        rate("sicepat", "reg", 12_000L, false))));

        CartEstimate e = service.estimate(UID, cart(true), 5L);

        verify(quotes, never()).checkZone(anyLong(), anyList(), any());
        assertThat(e.reason()).isNull();
        assertThat(e.lowestFee()).isEqualTo(10_000L);
        assertThat(e.payable()).isEqualTo(210_000L);
        assertThat(e.freeShippingProgress().remaining()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("无默认地址 → 用 regionKecamatanId；都没有 → NO_DESTINATION、payable 不含运费")
    void regionFallbackAndNoDestination() {
        when(quotes.checkZone(eq(5L), anyList(), any())).thenReturn(Serviceability.of(
                quote("q", rate("jne", "reg", 10_000L, true))));
        assertThat(service.estimate(UID, cart(true), 5L).lowestFee()).isEqualTo(10_000L);

        CartEstimate none = service.estimate(UID, cart(true), null);
        assertThat(none.reason()).isEqualTo(CartEstimate.NO_DESTINATION);
        assertThat(none.lowestFee()).isNull();
        assertThat(none.payable()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("询价失败 → QUOTE_FAILED；不可配送 → NOT_SERVICEABLE")
    void failureReasons() {
        when(quotes.checkZone(eq(5L), anyList(), any())).thenThrow(new QuoteFailedException("x"));
        when(quotes.checkZone(eq(6L), anyList(), any()))
                .thenReturn(new Serviceability(Serviceability.Status.ZONE_NOT_OPEN, null));
        assertThat(service.estimate(UID, cart(true), 5L).reason()).isEqualTo(CartEstimate.QUOTE_FAILED);
        assertThat(service.estimate(UID, cart(true), 6L).reason())
                .isEqualTo(CartEstimate.NOT_SERVICEABLE);
    }

    @Test
    @DisplayName("计价器入参 = 最便宜 + AUTO 券 + 不用币 + 不保价：默认最优满减券被采用、saved 来自计价器")
    void autoCouponNoCoinNoInsurance() {
        ShopCoupon c = CouponFixtures.coupon(1, CouponAcquireType.CLAIM);   // 满减 10,000 门槛 50,000
        when(coupons.checkoutCandidates(eq(UID), any())).thenReturn(List.of(new OwnedCoupon(
                CouponFixtures.userCoupon(1, UID, c, UserCouponSource.CLAIM,
                        UserCouponStatus.AVAILABLE, NOW.plus(Duration.ofDays(3))), c)));
        when(quotes.checkZone(eq(5L), anyList(), any())).thenReturn(Serviceability.of(
                quote("q", rate("jne", "reg", 10_000L, true))));

        CartEstimate e = service.estimate(UID, cart(true), 5L);

        assertThat(e.discountCouponAmount()).isEqualTo(10_000L);
        assertThat(e.saved()).isEqualTo(10_000L);
        assertThat(e.payable()).as("190,000 + 10,000 运费，无保价、无币").isEqualTo(200_000L);
        verifyNoInteractions(wallet);
        verify(coinRulesRepo, never()).findById(any());
        verify(quotes, never()).checkAddress(anyString(), anyString(), anyList(), any());
    }
}
