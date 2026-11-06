package com.tailtopia.shop.order.service;

import static com.tailtopia.shop.support.CheckoutFixtures.address;
import static com.tailtopia.shop.support.CheckoutFixtures.quote;
import static com.tailtopia.shop.support.CheckoutFixtures.rate;
import static com.tailtopia.shop.support.CheckoutFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.logistics.CourierRate;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.PlaceOrderRequest;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import com.tailtopia.shop.order.service.CheckoutQuoteService.CheckoutComputation;
import com.tailtopia.shop.order.service.CheckoutQuoteService.QuoteOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：旧结算模式下单装配（v1.4.2 shop Story 3.2 AC5，S-4）。 */
class LegacyCheckoutAdapterTest {

    private static final long UID = 7L;

    private final CheckoutQuoteService chain = mock(CheckoutQuoteService.class);
    private final LegacyCheckoutAdapter adapter = new LegacyCheckoutAdapter(chain);
    private final ShopSku s1 = sku(1, "s1", 1, 100_000L, true);
    private final ShippingAddress addr = address(UID, "a1", "Kebayoran", "12160");

    private ResolvedCheckoutLine line(String reason) {
        return new ResolvedCheckoutLine("s1", "p1", "Produk", "3 kg", 100_000L, 1, null,
                ReturnPolicy.RETURNABLE, reason, 10L, ProductCategory.MAKANAN, null, null, s1);
    }

    private void givenComputation(List<ResolvedCheckoutLine> lines, QuoteOutcome quote,
            CourierRate selected) {
        CheckoutInputs in = new CheckoutInputs(CheckoutPreviewRequest.Source.CART, null, lines,
                addr, List.of(), Map.of(), new FreeShippingRule(0L, null), 50, true, true,
                1_000_000L, 0L);
        when(chain.compute(eq(UID), any())).thenReturn(new CheckoutComputation(in, quote, selected,
                false, false, true, null, null, Instant.now(), null, null));
    }

    @Test
    @DisplayName("无 quoteToken 的旧请求：CART · 最便宜快递 · 无券 · useCoin=true · 不保价 · 视为同意 · LEGACY")
    void legacyDefaults() {
        CourierRate cheapest = rate("jne", "reg", 15_000L, true);
        givenComputation(List.of(line(null)), new QuoteOutcome(QuoteOutcome.Status.OK,
                quote("qt-1", cheapest, rate("sicepat", "best", 22_000L, false))), cheapest);

        PlaceCommand cmd = adapter.placeCommand(UID, PlaceOrderRequest.legacy(" a1 ", "SHOP_HOME", "T"));

        assertThat(cmd.checkoutSource()).isEqualTo(CheckoutSource.LEGACY);
        assertThat(cmd.quoteToken()).isEqualTo("qt-1");
        assertThat(cmd.agreeReturnPolicy()).isTrue();
        assertThat(cmd.fallbackEntrySource()).isEqualTo("SHOP_HOME");
        assertThat(cmd.fallbackTriggerType()).isEqualTo("T");
        assertThat(cmd.checkout().couponsEnabled()).isFalse();
        assertThat(cmd.checkout().defaultAddressFallback()).isFalse();
        CheckoutPreviewRequest r = cmd.checkout().request();
        assertThat(r.sourceValue()).isEqualTo(CheckoutPreviewRequest.Source.CART);
        assertThat(r.addressTokenOrNull()).isEqualTo("a1");
        assertThat(r.courierKey()).isEqualTo("jne:reg");
        assertThat(r.insuranceValue()).isFalse();
        assertThat(r.useCoinValue()).isTrue();
        assertThat(r.discountCouponToken()).isNull();
        assertThat(r.freeShippingCouponToken()).isNull();

        // 询价走旧预览同一组默认值（不指定快递 → 计价链路取最便宜）
        ArgumentCaptor<CheckoutCommand> probe = ArgumentCaptor.forClass(CheckoutCommand.class);
        verify(chain).compute(eq(UID), probe.capture());
        assertThat(probe.getValue().request().courierKey()).isNull();
        assertThat(probe.getValue().couponsEnabled()).isFalse();
        assertThat(probe.getValue()).isEqualTo(adapter.previewCommand("a1"));
    }

    @Test
    @DisplayName("询价失败 → 503 quote-failed")
    void quoteFailed() {
        givenComputation(List.of(line(null)), new QuoteOutcome(QuoteOutcome.Status.QUOTE_FAILED, null), null);
        assertThatThrownBy(() -> adapter.placeCommand(UID, PlaceOrderRequest.legacy("a1", null, null)))
                .isInstanceOf(QuoteFailedException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.QUOTE_FAILED);
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    @DisplayName("0 家 / 区域未开通 → 422 not-placeable")
    void notServiceable() {
        givenComputation(List.of(line(null)), new QuoteOutcome(QuoteOutcome.Status.NOT_SERVICEABLE, null), null);
        assertThatThrownBy(() -> adapter.placeCommand(UID, PlaceOrderRequest.legacy("a1", null, null)))
                .satisfies(e -> {
                    assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.NOT_PLACEABLE);
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    @DisplayName("有不可买行 → 不报询价错误、不带 token（交给提交事务给 409 逐行明细）")
    void unavailableLinesDeferToPlace() {
        givenComputation(List.of(line(ResolvedCheckoutLine.REASON_DELISTED)),
                new QuoteOutcome(QuoteOutcome.Status.QUOTE_FAILED, null), null);
        PlaceCommand cmd = adapter.placeCommand(UID, PlaceOrderRequest.legacy("a1", null, null));
        assertThat(cmd.quoteToken()).isNull();
        assertThat(cmd.checkout().request().courierKey()).isNull();
    }

    @Test
    @DisplayName("空地址 → 422 validation「请选择收货地址」（旧文案与码），不询价")
    void addressRequired() {
        assertThatThrownBy(() -> adapter.placeCommand(UID, PlaceOrderRequest.legacy(" ", null, null)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请选择收货地址")
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION));
        verify(chain, never()).compute(any(Long.class), any());
    }

    @Test
    @DisplayName("🔴 不可售行：旧模式降级为 INSUFFICIENT_STOCK / available=0，新模式保留 NOT_SELLABLE")
    void notSellableReasonByMode() {
        ResolvedCheckoutLine l = line(ResolvedCheckoutLine.REASON_NOT_SELLABLE);
        UnavailableLine legacy = CheckoutService.unavailableOf(l, true);
        assertThat(legacy.reason()).isEqualTo(UnavailableLine.REASON_INSUFFICIENT_STOCK);
        assertThat(legacy.available()).isZero();
        UnavailableLine fresh = CheckoutService.unavailableOf(l, false);
        assertThat(fresh.reason()).isEqualTo(UnavailableLine.REASON_NOT_SELLABLE);
        assertThat(fresh.available()).isEqualTo(10L);
        assertThat(CheckoutService.unavailableOf(line(ResolvedCheckoutLine.REASON_OUT_OF_STOCK), true)
                .reason()).isEqualTo(UnavailableLine.REASON_INSUFFICIENT_STOCK);
    }
}
