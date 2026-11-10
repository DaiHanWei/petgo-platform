package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.support.CheckoutFixtures.rate;
import static com.tailtopia.shop.support.CheckoutFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.coupon.CouponFixtures;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.service.CouponInvalidException;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.PlaceOrderRequest;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：提交订单（v1.4.2 shop Story 3.2 AC3 / AC6 / AC8）。仓库 / 账本 / 钱包全 mock，计价器用真的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutPlaceServiceTest extends CheckoutPlaceTestBase {

    private final ShopSku s1 = sku(1, "s1", 1, 100_000L, true);
    private final ShopSku s2 = sku(2, "s2", 2, 50_000L, true);

    private static PlaceOrderRequest req(String source, String courierKey, String discount,
            String freeShipping, Boolean useCoin, Boolean agree) {
        return new PlaceOrderRequest("a1", "SHOP_HOME", null, source, null, QUOTE, courierKey,
                false, discount, freeShipping, useCoin, agree);
    }

    private static PlaceOrderRequest cartReq() {
        return req("CART", "jne:reg", null, null, false, true);
    }

    private ShopOrder place(PlaceOrderRequest r) {
        return service.place(UID, PlaceCommand.of(r), null);
    }

    private OwnedCoupon coupon(long id, CouponKind kind, long amount, long minSpend,
            CouponScopeType scope, List<String> values) {
        ShopCoupon c = CouponFixtures.coupon(id, CouponAcquireType.CLAIM, scope, values);
        ReflectionTestUtils.setField(c, "kind", kind);
        ReflectionTestUtils.setField(c, "amount", amount);
        ReflectionTestUtils.setField(c, "minSpend", minSpend);
        UserCoupon uc = CouponFixtures.userCoupon(id, UID, c, UserCouponSource.CLAIM,
                UserCouponStatus.AVAILABLE, Instant.now().plus(Duration.ofDays(3)));
        ReflectionTestUtils.setField(uc, "validFrom", Instant.now().minus(Duration.ofDays(1)));
        return new OwnedCoupon(uc, c);
    }

    private OwnedCoupon coupon(long id, CouponKind kind, long amount, long minSpend) {
        return coupon(id, kind, amount, minSpend, CouponScopeType.ALL, List.of());
    }

    private List<ResolvedCheckoutLine> twoLines() {
        return List.of(line(s1, 1), line(s2, 2));
    }

    // ------------------------------------------------------------------ 前置校验

    @Test
    @DisplayName("agreeReturnPolicy≠true → 422 validation，不解析行、不建单")
    void agreeRequired() {
        assertThatThrownBy(() -> place(req("CART", "jne:reg", null, null, true, null)))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION));
        verify(loader, never()).load(anyLong(), any(), any());
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("source 非法 → 422 validation")
    void illegalSource() {
        assertThatThrownBy(() -> place(req("WISHLIST", "jne:reg", null, null, true, true)))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION));
    }

    @Test
    @DisplayName("无可结算行 → 422 not-placeable")
    void noLines() {
        givenInputs(inputs(List.of(), List.of(), 0L));
        assertThatThrownBy(() -> place(cartReq()))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.NOT_PLACEABLE));
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("🔴 有不可买行 → 409 unavailable-lines，逐行（新模式保留 NOT_SELLABLE）")
    void unavailableLinesListedPerLine() {
        ShopSku dead = sku(3, "s3", 3, 10_000L, true);
        ShopSku unsellable = sku(4, "s4", 4, 10_000L, false);
        givenInputs(inputs(List.of(line(s1, 1),
                line(dead, "p3", 1, ResolvedCheckoutLine.REASON_DELISTED, null),
                line(unsellable, "p4", 1, ResolvedCheckoutLine.REASON_NOT_SELLABLE, 5L),
                line(s2, "p2", 9, ResolvedCheckoutLine.REASON_INSUFFICIENT_STOCK, 3L)), List.of(), 0L));

        assertThatThrownBy(() -> place(cartReq()))
                .isInstanceOf(CheckoutUnavailableException.class)
                .satisfies(e -> {
                    CheckoutUnavailableException ex = (CheckoutUnavailableException) e;
                    assertThat(ex.getType()).isEqualTo(ErrorTypes.UNAVAILABLE_LINES);
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.problemExtensions()).containsKey("unavailableLines");
                    assertThat(ex.getLines()).extracting(UnavailableLine::reason).containsExactly(
                            UnavailableLine.REASON_DELISTED, UnavailableLine.REASON_NOT_SELLABLE,
                            UnavailableLine.REASON_INSUFFICIENT_STOCK);
                    assertThat(ex.getLines().get(2).available()).isEqualTo(3L);
                });
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("无地址 → 422 not-placeable；区域未开通 → 422 not-placeable")
    void addressRequiredAndZoneOpen() {
        givenInputs(inputs(CheckoutPreviewRequest.Source.CART, null, twoLines(), List.of(),
                new FreeShippingRule(0L, null), true, 0L, null));
        assertThatThrownBy(() -> place(cartReq()))
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.NOT_PLACEABLE));

        givenInputs(inputs(twoLines(), List.of(), 0L));
        when(zones.findByKecamatanAndActiveTrue(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> place(cartReq()))
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.NOT_PLACEABLE));
        verify(orders, never()).save(any());
    }

    // ------------------------------------------------------------------ 报价绑定（I7）

    static Stream<Arguments> quoteMismatches() {
        return Stream.of(
                Arguments.of("报价不存在", "missing"),
                Arguments.of("报价已过期", "expired"),
                Arguments.of("邮编与地址不符", "postal"),
                Arguments.of("items_hash 与本单行不符", "hash"),
                Arguments.of("courierKey 不在报价中", "courier"));
    }

    @ParameterizedTest(name = "{0} → 409 quote-expired")
    @MethodSource("quoteMismatches")
    @DisplayName("🔴 报价绑定任一不符 → 409 quote-expired（零网络调用）")
    void quoteBinding(String label, String kind) {
        List<ResolvedCheckoutLine> lines = twoLines();
        givenInputs(inputs(lines, List.of(), 0L));
        String hash = com.tailtopia.shop.logistics.PackageItems.itemsHash(
                lines.stream().map(ResolvedCheckoutLine::toPackageItem).toList());
        Instant future = Instant.now().plus(Duration.ofMinutes(30));
        String courierKey = "jne:reg";
        switch (kind) {
            case "missing" -> { }
            case "expired" -> givenQuote("12160", hash, Instant.now().minusSeconds(1),
                    rate("jne", "reg", 20_000L, true));
            case "postal" -> givenQuote("10110", hash, future, rate("jne", "reg", 20_000L, true));
            case "hash" -> givenQuote("12160", "0".repeat(64), future, rate("jne", "reg", 20_000L, true));
            case "courier" -> {
                givenQuote("12160", hash, future, rate("jne", "reg", 20_000L, true));
                courierKey = "sicepat:best";
            }
            default -> throw new IllegalArgumentException(kind);
        }
        String key = courierKey;
        assertThatThrownBy(() -> place(req("CART", key, null, null, false, true)))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.QUOTE_EXPIRED);
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(409);
                });
        verify(orders, never()).save(any());
        verify(quoteChain, never()).compute(anyLong(), any());
    }

    // ------------------------------------------------------------------ 建单与金额

    @Test
    @DisplayName("🔴 订单头金额 = 计价器出参：shipping_discount 落库为负、total_amount == payable、快递五列与报价 id")
    void orderHeaderFromPricing() {
        List<ResolvedCheckoutLine> lines = twoLines();   // 100,000 + 2 × 50,000 = 200,000
        givenInputs(inputs(CheckoutPreviewRequest.Source.CART, null, lines, List.of(),
                new FreeShippingRule(150_000L, null), true, 0L, addr));
        givenQuote(lines, rate("jne", "reg", 18_000L, true), rate("sicepat", "best", 25_000L, false));

        ShopOrder o = place(req("CART", "sicepat:best", null, null, false, true));

        // 满额免运：减免 = min(所选 25,000, 最低报价 18,000) = 18,000
        assertThat(o.getGoodsSubtotal()).isEqualTo(200_000L);
        assertThat(o.getShippingFee()).isEqualTo(25_000L);
        assertThat(o.getShippingDiscount()).isEqualTo(-18_000L);
        assertThat(o.getShippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        assertThat(o.getTotalAmount()).isEqualTo(207_000L);
        assertThat(o.getTotalAmount()).isEqualTo(o.getGoodsSubtotal() - o.getDiscountCouponAmount()
                + o.getShippingFee() + o.getShippingDiscount() + o.getInsuranceFee());
        assertThat(o.getCourierCode()).isEqualTo("sicepat");
        assertThat(o.getCourierServiceCode()).isEqualTo("best");
        assertThat(o.getCourierName()).isEqualTo("SICEPAT");
        assertThat(o.getCourierServiceName()).isEqualTo("BEST");
        assertThat(o.getCourierEtaText()).isEqualTo("2 - 3");
        assertThat(o.getShippingQuoteId()).isEqualTo(77L);
        assertThat(o.getCheckoutSource()).isEqualTo(CheckoutSource.CART);
        assertThat(o.getCoinSelected()).isFalse();
        assertThat(o.getCoinAmount()).isZero();
        assertThat(o.getCashAmount()).isEqualTo(207_000L);
        assertThat(o.getPayChannel()).isEqualTo(PayChannel.QRIS);
        assertThat(o.getExpiresAt()).isEqualTo(o.getCreatedAt().plus(ShopOrder.PAYMENT_WINDOW));
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), any(), any(), any());
        verify(paidTransition, never()).markPaid(any(), any());
    }

    @Test
    @DisplayName("🔴 NFR7：用 50,000 满减券时各行 unit_price = SKU 原价、goods_subtotal 为券前；discount_eligible 按范围")
    void unitPriceNotAllocatedAndDiscountEligible() {
        List<ResolvedCheckoutLine> lines = List.of(line(s1, "pA", 1, null, 10L),
                line(s2, "pB", 2, null, 10L));
        OwnedCoupon disc = coupon(11, CouponKind.DISCOUNT, 50_000L, 50_000L,
                CouponScopeType.PRODUCT, List.of("pA"));
        givenInputs(inputs(lines, List.of(disc), 0L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        ShopOrder o = place(req("CART", "jne:reg", "uc11", null, false, true));

        assertThat(o.getGoodsSubtotal()).isEqualTo(200_000L);
        assertThat(o.getDiscountCouponAmount()).isEqualTo(50_000L);
        assertThat(o.getDiscountUserCouponId()).isEqualTo(11L);
        assertThat(o.getTotalAmount()).isEqualTo(170_000L);
        assertThat(savedLines).extracting(ShopOrderLine::getUnitPrice)
                .containsExactly(100_000L, 50_000L);
        assertThat(savedLines).extracting(ShopOrderLine::isDiscountEligible)
                .containsExactly(true, false);
        verify(coupons).lock(eq(UID), eq(900L), eq(List.of("uc11")), any());
    }

    @Test
    @DisplayName("🔴 S-3 提交时扣币：refType=SHOP_ORDER、键 shop-order:{token}；调用顺序 lock 库存 → 占券 → 扣币")
    void debitAtSubmitInOrder() {
        List<ResolvedCheckoutLine> lines = twoLines();
        OwnedCoupon disc = coupon(11, CouponKind.DISCOUNT, 10_000L, 0L);
        givenInputs(inputs(lines, List.of(disc), 30_000L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        ShopOrder o = place(req("CART", "jne:reg", "uc11", null, true, true));

        assertThat(o.getCoinAmount()).isEqualTo(30_000L);
        assertThat(o.getCashAmount()).isEqualTo(180_000L);
        assertThat(o.getPayChannel()).isEqualTo(PayChannel.MIXED);
        InOrder order = inOrder(inventory, coupons, wallet);
        order.verify(inventory, times(2)).lock(anyLong(), anyLong());
        order.verify(coupons).lock(eq(UID), eq(900L), any(), any());
        order.verify(wallet).debit(UID, 30_000L, PawCoinTxnType.SPEND, "SHOP_ORDER", 900L,
                "shop-order:" + ORDER_TOKEN);
        verify(paidTransition, never()).markPaid(any(), any());
    }

    @Test
    @DisplayName("🔴 券被并发占用 → 409 coupon-invalid，且不扣币")
    void couponLockFailsBeforeDebit() {
        List<ResolvedCheckoutLine> lines = twoLines();
        OwnedCoupon disc = coupon(11, CouponKind.DISCOUNT, 10_000L, 0L);
        givenInputs(inputs(lines, List.of(disc), 30_000L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));
        doThrow(new CouponInvalidException("uc11")).when(coupons)
                .lock(anyLong(), anyLong(), any(), any());

        assertThatThrownBy(() -> place(req("CART", "jne:reg", "uc11", null, true, true)))
                .isInstanceOf(CouponInvalidException.class)
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.COUPON_INVALID));
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), any(), any(), any());
        verify(carts, never()).remove(anyLong(), anyString());
    }

    @Test
    @DisplayName("MANUAL 券不可用（未达门槛 / 不在候选里）→ 409 coupon-invalid，不建单")
    void manualCouponInvalid() {
        List<ResolvedCheckoutLine> lines = twoLines();
        OwnedCoupon disc = coupon(11, CouponKind.DISCOUNT, 10_000L, 500_000L);
        givenInputs(inputs(lines, List.of(disc), 0L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        assertThatThrownBy(() -> place(req("CART", "jne:reg", "uc11", null, false, true)))
                .isInstanceOf(CouponInvalidException.class)
                .satisfies(e -> assertThat(((CouponInvalidException) e).getUserCouponTokens())
                        .containsExactly("uc11"));
        assertThatThrownBy(() -> place(req("CART", "jne:reg", "uc-not-mine", null, false, true)))
                .isInstanceOf(CouponInvalidException.class);
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("🔴 免运券被判 RULE_BETTER → 不报错、不占用（满额免运胜出）")
    void freeShippingRuleBetterNotLocked() {
        List<ResolvedCheckoutLine> lines = twoLines();
        OwnedCoupon fs = coupon(12, CouponKind.FREE_SHIPPING, 5_000L, 0L);
        givenInputs(inputs(CheckoutPreviewRequest.Source.CART, null, lines, List.of(fs),
                new FreeShippingRule(100_000L, null), true, 0L, addr));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        ShopOrder o = place(req("CART", "jne:reg", null, "uc12", false, true));

        assertThat(o.getShippingDiscountSource()).isEqualTo(ShippingDiscountSource.FREE_SHIPPING_RULE);
        assertThat(o.getFreeShippingUserCouponId()).isNull();
        verify(coupons, never()).lock(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("现金段为 0（币足额）→ 同事务 markPaid(PURE_COIN)")
    void pureCoinMarksPaid() {
        List<ResolvedCheckoutLine> lines = twoLines();
        givenInputs(inputs(lines, List.of(), 5_000_000L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        ShopOrder o = place(req("CART", "jne:reg", null, null, true, true));

        assertThat(o.getCashAmount()).isZero();
        assertThat(o.getPayChannel()).isEqualTo(PayChannel.PAWCOIN);
        verify(wallet).debit(UID, 220_000L, PawCoinTxnType.SPEND, "SHOP_ORDER", 900L,
                "shop-order:" + ORDER_TOKEN);
        verify(paidTransition).markPaid(o, PaidSource.PURE_COIN);
    }

    @Test
    @DisplayName("🔴 C-4 应付为 0（满减 + 免运券抵光）→ markPaid(ZERO_PAY)，不扣币、不走 PURE_COIN")
    void zeroPayable() {
        List<ResolvedCheckoutLine> lines = List.of(line(s2, 1));   // 50,000
        OwnedCoupon disc = coupon(11, CouponKind.DISCOUNT, 50_000L, 0L);
        OwnedCoupon fs = coupon(12, CouponKind.FREE_SHIPPING, 30_000L, 0L);
        givenInputs(inputs(lines, List.of(disc, fs), 1_000_000L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        ShopOrder o = place(req("CART", "jne:reg", "uc11", "uc12", true, true));

        assertThat(o.getTotalAmount()).isZero();
        assertThat(o.getCoinAmount()).isZero();
        assertThat(o.getCashAmount()).isZero();
        assertThat(o.getShippingDiscount()).isEqualTo(-20_000L);
        verify(paidTransition).markPaid(o, PaidSource.ZERO_PAY);
        verify(paidTransition, never()).markPaid(any(), eq(PaidSource.PURE_COIN));
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> locked = ArgumentCaptor.forClass(Collection.class);
        verify(coupons).lock(eq(UID), eq(900L), locked.capture(), any());
        assertThat(locked.getValue()).containsExactly("uc11", "uc12");
    }

    // ------------------------------------------------------------------ 购物车与归因

    @Test
    @DisplayName("CART：只删已下单行；归因优先购物车行、请求值兜底")
    void cartRemovesOnlyPlacedLines() {
        ResolvedCheckoutLine withSource = new ResolvedCheckoutLine("s1", "p1", "Produk", "3 kg",
                100_000L, 1, null, com.tailtopia.shop.domain.ReturnPolicy.RETURNABLE, null, 10L,
                com.tailtopia.shop.domain.ProductCategory.MAKANAN, "PROFILE_REC", "REPURCHASE", s1);
        List<ResolvedCheckoutLine> lines = List.of(withSource, line(s2, 1));
        givenInputs(inputs(lines, List.of(), 0L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        place(cartReq());

        verify(carts).remove(UID, "s1");
        verify(carts).remove(UID, "s2");
        verify(carts, times(2)).remove(anyLong(), anyString());
        assertThat(savedLines).extracting(ShopOrderLine::getEntrySource)
                .containsExactly("PROFILE_REC", "SHOP_HOME");
        assertThat(savedLines).extracting(ShopOrderLine::getTriggerType)
                .containsExactly("REPURCHASE", null);
    }

    @Test
    @DisplayName("🔴 BUY_NOW：不碰购物车；归因取 buyNow.entrySource；checkout_source=BUY_NOW")
    void buyNowDoesNotTouchCart() {
        List<ResolvedCheckoutLine> lines = List.of(line(s1, 2));
        givenInputs(inputs(CheckoutPreviewRequest.Source.BUY_NOW, null, lines, List.of(),
                new FreeShippingRule(0L, null), true, 0L, addr));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        ShopOrder o = service.place(UID, PlaceCommand.of(new PlaceOrderRequest("a1", null, null,
                "BUY_NOW", new CheckoutPreviewRequest.BuyNow("s1", 2, "PRODUCT_DETAIL"), QUOTE,
                "jne:reg", false, null, null, false, true)), null);

        assertThat(o.getCheckoutSource()).isEqualTo(CheckoutSource.BUY_NOW);
        verify(carts, never()).remove(anyLong(), anyString());
        assertThat(savedLines).extracting(ShopOrderLine::getEntrySource).containsExactly("PRODUCT_DETAIL");
    }

    @Test
    @DisplayName("幂等：同 key 重放取回既有订单，不解析行、不扣币")
    void idempotentReplay() {
        ShopOrder existing = ShopOrder.place("old", "TOKO-1", Instant.now(), UID, 1L, 0L, 0L,
                new com.tailtopia.shop.order.domain.AddressSnapshot("B", "+62", "P", "K", "Kec", "J", "12160"));
        when(idempotency.findResourceId("shop-checkout:" + UID + ":k1")).thenReturn(Optional.of(5L));
        when(orders.findById(5L)).thenReturn(Optional.of(existing));

        assertThat(service.place(UID, PlaceCommand.of(cartReq()), "k1")).isSameAs(existing);
        verify(loader, never()).load(anyLong(), any(), any());
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("useCoin 但规则关闭 → 不扣币；coin_selected 记请求值")
    void coinDisabledByRules() {
        List<ResolvedCheckoutLine> lines = twoLines();
        givenInputs(inputs(CheckoutPreviewRequest.Source.CART, null, lines, List.of(),
                new FreeShippingRule(0L, null), false, 1_000_000L, addr));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));

        ShopOrder o = place(req("CART", "jne:reg", null, null, true, true));

        assertThat(o.getCoinAmount()).isZero();
        assertThat(o.getCoinSelected()).isTrue();
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("保价：所选快递支持保价才计保价费（ceil(商品实付 × 50bp)）")
    void insuranceOnlyWhenSupported() {
        List<ResolvedCheckoutLine> lines = twoLines();
        givenInputs(inputs(lines, List.of(), 0L));
        givenQuote(lines, rate("jne", "reg", 20_000L, true));

        ShopOrder o = service.place(UID, PlaceCommand.of(new PlaceOrderRequest("a1", null, null,
                "CART", null, QUOTE, "jne:reg", true, null, null, false, true)), null);

        assertThat(o.isInsuranceSelected()).isTrue();
        assertThat(o.getInsuranceFee()).isEqualTo(1_000L);
        assertThat(o.getTotalAmount()).isEqualTo(221_000L);
    }

    @Test
    @DisplayName("购物车行解析入参：CartView 仅作旧模式判序用，新模式空车按 not-placeable")
    void emptyCartNewMode() {
        givenInputs(inputs(CheckoutPreviewRequest.Source.CART,
                new CartView(List.of(), List.of(), 0L, 0L, 0, 0), List.of(), List.of(),
                new FreeShippingRule(0L, null), true, 0L, addr));
        assertThatThrownBy(() -> place(cartReq()))
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.NOT_PLACEABLE));
    }

    // ---------- 复审修复（资金）：同 Idempotency-Key 并发 ----------

    @Test
    @DisplayName("🔴 同 key 抢占失败且映射已在 → 取回原单，不解析行、不扣币")
    void claimLostReturnsExistingOrder() {
        ShopOrder existing = ShopOrder.place("old", "TOKO-1", Instant.now(), UID, 1L, 0L, 0L,
                new com.tailtopia.shop.order.domain.AddressSnapshot("B", "+62", "P", "K", "Kec", "J", "12160"));
        String key = "shop-checkout:" + UID + ":k2";
        when(idempotency.findResourceId(key)).thenReturn(Optional.empty(), Optional.of(5L));
        when(idempotency.tryClaim(eq(key), any())).thenReturn(false);
        when(orders.findById(5L)).thenReturn(Optional.of(existing));

        assertThat(service.place(UID, PlaceCommand.of(cartReq()), "k2")).isSameAs(existing);
        verify(loader, never()).load(anyLong(), any(), any());
        verify(wallet, never()).debit(anyLong(), anyLong(), any(), any(), any(), any());
        verify(idempotency, never()).releaseClaim(any());
    }

    @Test
    @DisplayName("🔴 同 key 抢占失败且尚无映射（前一个仍在处理）→ 409 conflict，不建单")
    void claimLostWhileInFlightIs409() {
        when(idempotency.tryClaim(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.place(UID, PlaceCommand.of(cartReq()), "k3"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.CONFLICT);
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(409);
                });
        verify(loader, never()).load(anyLong(), any(), any());
        verify(orders, never()).save(any());
    }

    @Test
    @DisplayName("抢占成功：用户隔离的键 + TTL；成功写映射并释放占位；失败（回滚）同样释放、可重试")
    void claimReleasedOnSuccessAndFailure() {
        List<ResolvedCheckoutLine> lines = twoLines();
        givenInputs(inputs(lines, List.of(), 0L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));
        String key = "shop-checkout:" + UID + ":k4";

        place4();
        verify(idempotency).tryClaim(key, CheckoutService.CLAIM_TTL);
        verify(idempotency).store(key, 900L);
        verify(idempotency).releaseClaim(key);

        when(quoteRecords.findByPublicToken(QUOTE)).thenReturn(Optional.empty());
        assertThatThrownBy(this::place4).isInstanceOf(AppException.class);
        verify(idempotency, times(2)).releaseClaim(key);
    }

    private ShopOrder place4() {
        return service.place(UID, PlaceCommand.of(cartReq()), "k4");
    }

    @Test
    @DisplayName("无 Idempotency-Key → 不抢占、不写映射（与改动前一致）")
    void noKeyNoClaim() {
        List<ResolvedCheckoutLine> lines = twoLines();
        givenInputs(inputs(lines, List.of(), 0L));
        givenQuote(lines, rate("jne", "reg", 20_000L, false));
        place(cartReq());
        verify(idempotency, never()).tryClaim(any(), any());
        verify(idempotency, never()).releaseClaim(any());
    }
}
