package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.support.CheckoutFixtures.NOW;
import static com.tailtopia.shop.support.CheckoutFixtures.address;
import static com.tailtopia.shop.support.CheckoutFixtures.cartLine;
import static com.tailtopia.shop.support.CheckoutFixtures.coinRules;
import static com.tailtopia.shop.support.CheckoutFixtures.product;
import static com.tailtopia.shop.support.CheckoutFixtures.quote;
import static com.tailtopia.shop.support.CheckoutFixtures.rate;
import static com.tailtopia.shop.support.CheckoutFixtures.settings;
import static com.tailtopia.shop.support.CheckoutFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.service.CouponQueryService;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.logistics.QuoteResult;
import com.tailtopia.shop.logistics.Serviceability;
import com.tailtopia.shop.logistics.ShippingQuoteService;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.CheckoutQuoteView;
import com.tailtopia.shop.order.repository.ShopPawcoinRulesRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * L0：结算试算编排（v1.4.2 shop Story 3.1 AC1）。真 {@link CheckoutLineResolver} + 真计价器，
 * 仓库 / 询价 / 钱包全 mock（无 DB、无 Spring 上下文）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutQuoteServiceTest {

    private static final long UID = 42L;

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

    private CheckoutQuoteService service;

    private final List<ShopSku> skuRows = new ArrayList<>();
    private final List<ShopProduct> productRows = new ArrayList<>();
    private final List<OwnedCoupon> candidates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CheckoutLineResolver resolver = new CheckoutLineResolver(carts, addresses, addressRepo,
                skus, products, inventory, imageUrls, coupons, settingsRepo, coinRulesRepo, wallet);
        service = new CheckoutQuoteService(resolver, quotes, Clock.fixed(NOW, ZoneOffset.UTC));
        when(skus.findByPublicTokenIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> tokens = inv.getArgument(0);
            return skuRows.stream().filter(s -> tokens.contains(s.getPublicToken())).toList();
        });
        when(products.findAllById(any())).thenAnswer(inv -> productRows);
        when(products.findById(anyLong())).thenAnswer(inv -> productRows.stream()
                .filter(p -> p.getId().equals(inv.getArgument(0))).findFirst());
        when(coupons.checkoutCandidates(eq(UID), any())).thenAnswer(inv -> candidates);
        when(coupons.owned(anyLong(), anyString())).thenReturn(Optional.empty());
        when(settingsRepo.findById(ShippingSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings(0L, null, 50)));
        when(coinRulesRepo.findById(any())).thenReturn(Optional.of(coinRules(true, true, 1_000_000L)));
        when(wallet.balanceOf(UID)).thenReturn(0L);
        when(imageUrls.publicUrl(any())).thenReturn("https://cdn/p.jpg");
    }

    // ------------------------------------------------------------------ helpers

    private void seedSku(long id, String token, long price, boolean active, boolean sellable,
            ReturnPolicy policy) {
        productRows.add(product(id, "p" + token, active, policy));
        skuRows.add(sku(id, token, id, price, sellable));
    }

    private void cartWith(CartView.CartLine... lines) {
        List<CartView.CartLine> valid = List.of(lines);
        when(carts.viewReadOnly(UID)).thenReturn(new CartView(valid, List.of(), 0L, 0L, 0, 0));
    }

    private ShippingAddress defaultAddress() {
        ShippingAddress a = address(UID, "addr1", "Kebayoran", "12160");
        when(addressRepo.findByUserIdAndIsDefaultTrue(UID)).thenReturn(Optional.of(a));
        return a;
    }

    private void quoteReturns(QuoteResult q) {
        when(quotes.checkAddress(anyString(), anyString(), anyList(), any()))
                .thenReturn(Serviceability.of(q));
    }

    private static CheckoutPreviewRequest cart() {
        return req("CART", null, null, null, null, null, null);
    }

    private static CheckoutPreviewRequest req(String source, String courierKey, Boolean insurance,
            String mode, String discount, String freeShipping, Boolean useCoin) {
        return new CheckoutPreviewRequest(source, null, null, courierKey, insurance, mode, discount,
                freeShipping, useCoin);
    }

    private OwnedCoupon coupon(long id, CouponKind kind, long amount, long minSpend,
            Duration validFor) {
        ShopCoupon c = com.tailtopia.shop.coupon.CouponFixtures.coupon(id, CouponAcquireType.CLAIM);
        ReflectionTestUtils.setField(c, "kind", kind);
        ReflectionTestUtils.setField(c, "amount", amount);
        ReflectionTestUtils.setField(c, "minSpend", minSpend);
        UserCoupon uc = com.tailtopia.shop.coupon.CouponFixtures.userCoupon(id, UID, c,
                UserCouponSource.CLAIM, UserCouponStatus.AVAILABLE, NOW.plus(validFor));
        ReflectionTestUtils.setField(uc, "validFrom", NOW.minus(Duration.ofDays(1)));
        OwnedCoupon o = new OwnedCoupon(uc, c);
        candidates.add(o);
        return o;
    }

    // ------------------------------------------------------------------ 行来源

    @Test
    @DisplayName("CART：只用购物车选中有效行（selectedLines），未勾选行不进入计价")
    void cartUsesSelectedValidLinesOnly() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        seedSku(2, "s2", 50_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true),
                cartLine("s2", "ps2", 50_000L, 1, 10, false));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.lines()).extracting(CheckoutQuoteView.QuoteLine::skuToken).containsExactly("s1");
        assertThat(v.pricing().goodsAmount()).isEqualTo(100_000L);
        assertThat(v.placeable()).isTrue();
        assertThat(v.notPlaceableReason()).isNull();
    }

    @Test
    @DisplayName("BUY_NOW：不读、不写购物车")
    void buyNowNeverTouchesCart() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        when(inventory.availableBySkuId(anyList())).thenReturn(Map.of(1L, 10L));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));

        CheckoutQuoteView v = service.preview(UID, new CheckoutPreviewRequest("BUY_NOW",
                new CheckoutPreviewRequest.BuyNow("s1", 2, "PRODUCT_DETAIL"), null, null, null,
                null, null, null, null));

        verifyNoInteractions(carts);
        assertThat(v.source()).isEqualTo("BUY_NOW");
        assertThat(v.lines()).singleElement().satisfies(l -> {
            assertThat(l.qty()).isEqualTo(2);
            assertThat(l.unavailableReason()).isNull();
        });
        assertThat(v.pricing().goodsAmount()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("BUY_NOW 不可售 SKU：行带 NOT_SELLABLE、placeable=false、不询价、不抛错")
    void buyNowUnsellableIsRenderable() {
        seedSku(1, "s1", 100_000L, true, false, ReturnPolicy.RETURNABLE);
        when(inventory.availableBySkuId(anyList())).thenReturn(Map.of(1L, 10L));
        defaultAddress();

        CheckoutQuoteView v = service.preview(UID, new CheckoutPreviewRequest("BUY_NOW",
                new CheckoutPreviewRequest.BuyNow("s1", 1, null), null, null, null, null, null,
                null, null));

        assertThat(v.lines().getFirst().unavailableReason()).isEqualTo("NOT_SELLABLE");
        assertThat(v.placeable()).isFalse();
        assertThat(v.notPlaceableReason()).isEqualTo(CheckoutQuoteView.UNAVAILABLE_LINES);
        verifyNoInteractions(quotes);
    }

    @Test
    @DisplayName("BUY_NOW：SKU 不存在 → 404；缺 buyNow / qty 越界 / source 非法 → 422")
    void buyNowValidation() {
        assertThatThrownBy(() -> service.preview(UID, new CheckoutPreviewRequest("BUY_NOW",
                new CheckoutPreviewRequest.BuyNow("nope", 1, null), null, null, null, null, null,
                null, null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(404);
        assertThatThrownBy(() -> service.preview(UID, req("BUY_NOW", null, null, null, null, null,
                null)))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
        assertThatThrownBy(() -> service.preview(UID, new CheckoutPreviewRequest("BUY_NOW",
                new CheckoutPreviewRequest.BuyNow("s1", 100, null), null, null, null, null, null,
                null, null)))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
        assertThatThrownBy(() -> service.preview(UID, req("WHATEVER", null, null, null, null, null,
                null)))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
        assertThatThrownBy(() -> service.preview(UID, req(null, null, null, null, null, null,
                null)))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ 地址与询价

    @Test
    @DisplayName("无地址（也无默认地址）→ NO_ADDRESS、不询价、pricing 不含运费")
    void noAddressDoesNotQuote() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));

        CheckoutQuoteView v = service.preview(UID, cart());

        verifyNoInteractions(quotes);
        assertThat(v.address()).isNull();
        assertThat(v.notPlaceableReason()).isEqualTo(CheckoutQuoteView.NO_ADDRESS);
        assertThat(v.pricing().shippingIncluded()).isFalse();
        assertThat(v.pricing().shippingFee()).isNull();
        assertThat(v.pricing().shippingDiscount()).isNull();
        assertThat(v.pricing().shippingDiscountSource()).isEqualTo("NONE");
        assertThat(v.pricing().payable()).isEqualTo(100_000L);
        assertThat(v.quoteToken()).isNull();
        assertThat(v.couriers()).isEmpty();
    }

    @Test
    @DisplayName("addressToken 非空走 require（非本人 → 404 由其保证）；不回落默认地址")
    void explicitAddressUsesRequire() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        when(addresses.require(UID, "other")).thenThrow(AppException.notFound("地址不存在"));

        assertThatThrownBy(() -> service.preview(UID, new CheckoutPreviewRequest("CART", null,
                "other", null, null, null, null, null, null)))
                .extracting(e -> ((AppException) e).getStatus().value()).isEqualTo(404);
        verify(addressRepo, never()).findByUserIdAndIsDefaultTrue(anyLong());
    }

    @Test
    @DisplayName("区域未开通 → NOT_SERVICEABLE（1.1 checkAddress 不调 Biteship）")
    void zoneNotOpenIsNotServiceable() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        when(quotes.checkAddress(anyString(), anyString(), anyList(), any()))
                .thenReturn(new Serviceability(Serviceability.Status.ZONE_NOT_OPEN, null));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.notPlaceableReason()).isEqualTo(CheckoutQuoteView.NOT_SERVICEABLE);
        assertThat(v.address()).isNotNull();
        assertThat(v.pricing().shippingIncluded()).isFalse();
        verify(quotes, never()).quote(anyString(), anyList(), any());
    }

    @Test
    @DisplayName("报价 0 家 → NOT_SERVICEABLE")
    void noCourierIsNotServiceable() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q0"));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.notPlaceableReason()).isEqualTo(CheckoutQuoteView.NOT_SERVICEABLE);
        assertThat(v.couriers()).isEmpty();
    }

    @Test
    @DisplayName("QuoteFailedException → QUOTE_FAILED、quoteToken=null、快递列表空，不抛 503")
    void quoteFailureIsRenderable() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        when(quotes.checkAddress(anyString(), anyString(), anyList(), any()))
                .thenThrow(new QuoteFailedException("x"));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.notPlaceableReason()).isEqualTo(CheckoutQuoteView.QUOTE_FAILED);
        assertThat(v.quoteToken()).isNull();
        assertThat(v.couriers()).isEmpty();
        assertThat(v.pricing().shippingFee()).isNull();
    }

    // ------------------------------------------------------------------ 快递与保价

    @Test
    @DisplayName("courierKey 仍在列表 → 保留；不在 → 最便宜；列表保持升序、cheapest 标第一家")
    void courierSelection() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true),
                rate("sicepat", "reg", 12_000L, false)));

        CheckoutQuoteView kept = service.preview(UID, req("CART", "sicepat:reg", null, null, null,
                null, null));
        assertThat(kept.selectedCourierKey()).isEqualTo("sicepat:reg");
        assertThat(kept.pricing().shippingFee()).isEqualTo(12_000L);
        assertThat(kept.quoteToken()).isEqualTo("q1");
        assertThat(kept.couriers()).extracting(CheckoutQuoteView.CourierOption::courierKey)
                .containsExactly("jne:reg", "sicepat:reg");
        assertThat(kept.couriers()).extracting(CheckoutQuoteView.CourierOption::cheapest)
                .containsExactly(true, false);

        CheckoutQuoteView fallback = service.preview(UID, req("CART", "anteraja:reg", null, null,
                null, null, null));
        assertThat(fallback.selectedCourierKey()).isEqualTo("jne:reg");
        assertThat(fallback.pricing().shippingFee()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("请求保价 + 所选快递不支持 → insurance=false、insuranceAvailable=false、保价费 0")
    void insuranceRequiresCourierSupport() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true),
                rate("sicepat", "reg", 12_000L, false)));

        CheckoutQuoteView unsupported = service.preview(UID, req("CART", "sicepat:reg", true, null,
                null, null, null));
        assertThat(unsupported.insuranceAvailable()).isFalse();
        assertThat(unsupported.insurance()).isFalse();
        assertThat(unsupported.pricing().insuranceFee()).isZero();

        CheckoutQuoteView supported = service.preview(UID, req("CART", "jne:reg", true, null,
                null, null, null));
        assertThat(supported.insuranceAvailable()).isTrue();
        assertThat(supported.insurance()).isTrue();
        // 费率 50bp：ceil(100,000 × 50 / 10,000) = 500（计价器给）
        assertThat(supported.pricing().insuranceFee()).isEqualTo(500L);
    }

    // ------------------------------------------------------------------ PawCoin

    @Test
    @DisplayName("useCoin=true 但规则 disabled → 有效 useCoin=false、不用币")
    void coinDisabledOverridesRequest() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));
        when(coinRulesRepo.findById(any())).thenReturn(Optional.of(coinRules(false, true, 1_000_000L)));
        when(wallet.balanceOf(UID)).thenReturn(500_000L);

        CheckoutQuoteView v = service.preview(UID, req("CART", null, null, null, null, null, true));

        assertThat(v.coin().enabled()).isFalse();
        assertThat(v.coin().useCoin()).isFalse();
        assertThat(v.coin().balance()).isEqualTo(500_000L);
        assertThat(v.pricing().coinAmount()).isZero();
        assertThat(v.pricing().cashAmount()).isEqualTo(110_000L);
    }

    @Test
    @DisplayName("useCoin 缺省 true；余额超上限 → capReached（计价器 coinCapped）")
    void coinDefaultsOnAndCapReached() {
        seedSku(1, "s1", 500_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 500_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));
        when(coinRulesRepo.findById(any())).thenReturn(Optional.of(coinRules(true, true, 100_000L)));
        when(wallet.balanceOf(UID)).thenReturn(400_000L);

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.coin().useCoin()).isTrue();
        assertThat(v.coin().cap()).isEqualTo(100_000L);
        assertThat(v.coin().capReached()).isTrue();
        assertThat(v.pricing().coinAmount()).isEqualTo(100_000L);

        CheckoutQuoteView off = service.preview(UID, req("CART", null, null, null, null, null,
                false));
        assertThat(off.coin().useCoin()).isFalse();
        assertThat(off.pricing().coinAmount()).isZero();
    }

    // ------------------------------------------------------------------ 券

    @Test
    @DisplayName("🔴 S-10：302,000、门槛 300,000、券减 5,000 → 默认用券、应付 317,000")
    void s10DefaultDiscountEvenIfItBreaksFreeShipping() {
        seedSku(1, "s1", 302_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 302_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 20_000L, true)));
        when(settingsRepo.findById(ShippingSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings(300_000L, null, 50)));
        coupon(1, CouponKind.DISCOUNT, 5_000L, 0L, Duration.ofDays(5));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.coupons().discount().selectedToken()).isEqualTo("uc1");
        assertThat(v.pricing().discountCouponAmount()).isEqualTo(5_000L);
        assertThat(v.pricing().shippingDiscount()).isZero();
        assertThat(v.pricing().payable()).isEqualTo(317_000L);
    }

    @Test
    @DisplayName("AUTO：取计价器最优组合；可用券 deduction 为若选它能减多少；不可用组带原因")
    void autoUsesCalculatorBest() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));
        coupon(1, CouponKind.DISCOUNT, 5_000L, 0L, Duration.ofDays(5));
        coupon(2, CouponKind.DISCOUNT, 8_000L, 0L, Duration.ofDays(1));      // 快过期
        coupon(3, CouponKind.DISCOUNT, 20_000L, 500_000L, Duration.ofDays(5)); // 未达门槛
        coupon(4, CouponKind.FREE_SHIPPING, 15_000L, 0L, Duration.ofDays(5));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.coupons().discount().selectedToken()).isEqualTo("uc2");
        assertThat(v.coupons().discount().available())
                .extracting(CheckoutQuoteView.CouponOption::userCouponToken)
                .containsExactly("uc2", "uc1");
        assertThat(v.coupons().discount().available().getFirst().deduction()).isEqualTo(8_000L);
        assertThat(v.coupons().discount().available().getFirst().expiringSoon()).isTrue();
        assertThat(v.coupons().discount().unavailable()).singleElement().satisfies(o -> {
            assertThat(o.userCouponToken()).isEqualTo("uc3");
            assertThat(o.unavailableReason()).isEqualTo("BELOW_MIN_SPEND");
            assertThat(o.deduction()).isZero();
        });
        assertThat(v.coupons().freeShipping().selectedToken()).isEqualTo("uc4");
        assertThat(v.pricing().shippingDiscount()).isEqualTo(10_000L);
        assertThat(v.pricing().shippingDiscountSource()).isEqualTo("COUPON");
        assertThat(v.pricing().savedAmount()).isEqualTo(18_000L);
        assertThat(v.pricing().payable()).isEqualTo(92_000L);
    }

    @Test
    @DisplayName("MANUAL：用请求里的 token；null = 不用该类券")
    void manualUsesRequestedTokens() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));
        coupon(1, CouponKind.DISCOUNT, 5_000L, 0L, Duration.ofDays(5));
        coupon(2, CouponKind.DISCOUNT, 8_000L, 0L, Duration.ofDays(5));
        coupon(4, CouponKind.FREE_SHIPPING, 15_000L, 0L, Duration.ofDays(5));

        CheckoutQuoteView v = service.preview(UID, req("CART", null, null, "MANUAL", "uc1", null,
                null));

        assertThat(v.coupons().discount().selectedToken()).isEqualTo("uc1");
        assertThat(v.coupons().freeShipping().selectedToken()).isNull();
        assertThat(v.pricing().discountCouponAmount()).isEqualTo(5_000L);
        assertThat(v.pricing().shippingDiscount()).isZero();
    }

    @Test
    @DisplayName("MANUAL 不可用券（未达门槛 / 查不到）→ 不采用、进 unavailable 组、selectedToken=null，试算不报错")
    void manualInvalidCouponIsNotApplied() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));
        coupon(3, CouponKind.DISCOUNT, 20_000L, 500_000L, Duration.ofDays(5));

        CheckoutQuoteView v = service.preview(UID, req("CART", null, null, "MANUAL", "uc3",
                "locked-or-not-mine", null));

        assertThat(v.coupons().discount().selectedToken()).isNull();
        assertThat(v.coupons().discount().unavailable())
                .extracting(CheckoutQuoteView.CouponOption::unavailableReason)
                .containsExactly("BELOW_MIN_SPEND");
        assertThat(v.coupons().freeShipping().selectedToken()).isNull();
        assertThat(v.coupons().freeShipping().unavailable()).singleElement().satisfies(o -> {
            assertThat(o.userCouponToken()).isEqualTo("locked-or-not-mine");
            assertThat(o.unavailableReason()).isEqualTo(CheckoutQuoteView.COUPON_UNAVAILABLE);
        });
        assertThat(v.pricing().discountCouponAmount()).isZero();
        assertThat(v.placeable()).isTrue();
    }

    @Test
    @DisplayName("🔴 1.2 复审：MANUAL 免运券减免为 0（运费 0）→ 不采用、selectedToken=null（不让用户以为券已用上）")
    void manualZeroBenefitFreeShippingIsNotShownAsSelected() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 0L, true)));
        coupon(4, CouponKind.FREE_SHIPPING, 15_000L, 0L, Duration.ofDays(5));

        CheckoutQuoteView v = service.preview(UID, req("CART", null, null, "MANUAL", null, "uc4",
                null));

        assertThat(v.coupons().freeShipping().selectedToken()).isNull();
        assertThat(v.coupons().freeShipping().available()).singleElement()
                .extracting(CheckoutQuoteView.CouponOption::deduction).isEqualTo(0L);
    }

    @Test
    @DisplayName("MANUAL 免运券输给满额免运 → RULE_BETTER 进 unavailable，减免来源 FREE_SHIPPING_RULE")
    void manualFreeShippingLosesToRule() {
        seedSku(1, "s1", 400_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 400_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));
        when(settingsRepo.findById(ShippingSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings(300_000L, null, 50)));
        coupon(4, CouponKind.FREE_SHIPPING, 15_000L, 0L, Duration.ofDays(5));

        CheckoutQuoteView v = service.preview(UID, req("CART", null, null, "MANUAL", null, "uc4",
                null));

        assertThat(v.coupons().freeShipping().selectedToken()).isNull();
        assertThat(v.coupons().freeShipping().unavailable())
                .extracting(CheckoutQuoteView.CouponOption::unavailableReason)
                .containsExactly("RULE_BETTER");
        assertThat(v.pricing().shippingDiscountSource()).isEqualTo("FREE_SHIPPING_RULE");
        assertThat(v.pricing().shippingDiscount()).isEqualTo(10_000L);
        assertThat(v.pricing().freeShippingProgress().remaining()).isZero();
    }

    // ------------------------------------------------------------------ 判序与退货规则

    @Test
    @DisplayName("notPlaceableReason 判序：NO_LINES 先于 NO_ADDRESS；INSUFFICIENT_STOCK 先于 NO_ADDRESS")
    void notPlaceableOrder() {
        cartWith();
        CheckoutQuoteView empty = service.preview(UID, cart());
        assertThat(empty.notPlaceableReason()).isEqualTo(CheckoutQuoteView.NO_LINES);
        assertThat(empty.placeable()).isFalse();

        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 5, 2, true));   // 选 5 件，只剩 2 件
        CheckoutQuoteView tight = service.preview(UID, cart());
        assertThat(tight.notPlaceableReason()).isEqualTo(CheckoutQuoteView.UNAVAILABLE_LINES);
        assertThat(tight.lines().getFirst().unavailableReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(tight.pricing().goodsAmount()).as("库存不足行仍计价").isEqualTo(500_000L);
    }

    @Test
    @DisplayName("strictestReturnPolicy = returnPolicyCopyKey = 生效规则最严一档（SKU 未设继承商品）")
    void strictestReturnPolicy() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        seedSku(2, "s2", 100_000L, true, true, ReturnPolicy.NO_RETURN_AFTER_OPEN);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true),
                cartLine("s2", "ps2", 100_000L, 1, 10, true));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.strictestReturnPolicy()).isEqualTo("NO_RETURN_AFTER_OPEN");
        assertThat(v.returnPolicyCopyKey()).isEqualTo("NO_RETURN_AFTER_OPEN");
        assertThat(v.lines()).extracting(CheckoutQuoteView.QuoteLine::returnPolicy)
                .containsExactly("RETURNABLE", "NO_RETURN_AFTER_OPEN");
    }

    // ------------------------------------------------------------------ 复审修复

    @Test
    @DisplayName("复审修复：CART 走只读读车 viewReadOnly（无车不 INSERT），从不调 view()")
    void cartUsesReadOnlyView() {
        when(carts.viewReadOnly(UID)).thenReturn(new CartView(List.of(), List.of(), 0L, 0L, 0, 0));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.notPlaceableReason()).isEqualTo(CheckoutQuoteView.NO_LINES);
        verify(carts, never()).view(anyLong());
    }

    @Test
    @DisplayName("🔴 复审修复（S-4 同口径）：库内勾着的失效行进 lines[] 带原因 → UNAVAILABLE_LINES，不静默少买")
    void dbSelectedInvalidLineBlocks() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        seedSku(2, "s2", 50_000L, false, true, ReturnPolicy.RETURNABLE);
        CartView.CartLine dead = new CartView.CartLine("s2", "ps2", "Produk", "3 kg", 50_000L, 1,
                null, 10L, CartView.REASON_DELISTED, null, null, false, true);
        CartView.CartLine deadUnpicked = new CartView.CartLine("s3", "ps3", "Produk", "3 kg",
                50_000L, 1, null, 0L, CartView.REASON_OUT_OF_STOCK, null, null, false, false);
        when(carts.viewReadOnly(UID)).thenReturn(new CartView(
                List.of(cartLine("s1", "ps1", 100_000L, 1, 10, true)), List.of(dead, deadUnpicked),
                100_000L, 100_000L, 1, 1));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));

        CheckoutQuoteView v = service.preview(UID, cart());

        assertThat(v.lines()).extracting(CheckoutQuoteView.QuoteLine::skuToken)
                .containsExactly("s1", "s2");
        assertThat(v.lines().get(1).unavailableReason()).isEqualTo("DELISTED");
        assertThat(v.notPlaceableReason()).isEqualTo(CheckoutQuoteView.UNAVAILABLE_LINES);
        assertThat(v.pricing().goodsAmount()).as("失效行不计价").isEqualTo(100_000L);
    }

    @Test
    @DisplayName("复审修复（AC1.6）：MANUAL 类目错配（免运券填进满减槽）→ 不采用、进满减组 unavailable")
    void manualKindMismatchGoesToSlotUnavailable() {
        seedSku(1, "s1", 100_000L, true, true, ReturnPolicy.RETURNABLE);
        cartWith(cartLine("s1", "ps1", 100_000L, 1, 10, true));
        defaultAddress();
        quoteReturns(quote("q1", rate("jne", "reg", 10_000L, true)));
        coupon(1, CouponKind.DISCOUNT, 5_000L, 0L, Duration.ofDays(5));
        coupon(4, CouponKind.FREE_SHIPPING, 15_000L, 0L, Duration.ofDays(5));

        CheckoutQuoteView v = service.preview(UID, req("CART", null, null, "MANUAL", "uc4", "uc1",
                null));

        assertThat(v.coupons().discount().selectedToken()).isNull();
        assertThat(v.coupons().freeShipping().selectedToken()).isNull();
        assertThat(v.coupons().discount().unavailable()).singleElement().satisfies(o -> {
            assertThat(o.userCouponToken()).isEqualTo("uc4");
            assertThat(o.kind()).isEqualTo("FREE_SHIPPING");
            assertThat(o.unavailableReason()).isEqualTo(CheckoutQuoteView.COUPON_UNAVAILABLE);
        });
        assertThat(v.coupons().freeShipping().unavailable())
                .extracting(CheckoutQuoteView.CouponOption::userCouponToken).containsExactly("uc1");
        assertThat(v.pricing().discountCouponAmount()).isZero();
        assertThat(v.pricing().shippingDiscount()).isZero();
    }
}
