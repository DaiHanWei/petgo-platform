package com.tailtopia.shop.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.pricing.PricingResult;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import com.tailtopia.shop.order.service.CheckoutInputs;
import com.tailtopia.shop.order.service.CheckoutQuoteService.CheckoutComputation;
import com.tailtopia.shop.order.service.CheckoutQuoteService.QuoteOutcome;
import com.tailtopia.shop.order.service.LegacyCheckoutAdapter;
import com.tailtopia.shop.order.service.ResolvedCheckoutLine;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import com.tailtopia.shop.logistics.QuoteResult;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：旧结算视图契约钉死（v1.4.2 shop Story 3.1 AC4 / AC8.1，S-4）。旧版 App {@code checkout_preview.dart}
 * 按这些键解析 —— 字段集合与顺序一个不增不减；新能力全在 {@link CheckoutQuoteView}。
 */
class CheckoutPreviewViewLegacyContractTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    @Test
    @DisplayName("CheckoutPreviewView 15 个组件名与顺序逐一钉住")
    void legacyComponentsFrozen() {
        assertThat(Arrays.stream(CheckoutPreviewView.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("address", "serviceable", "lines", "unavailableLines",
                        "goodsSubtotal", "shippingFee", "shippingDiscount", "payableTotal",
                        "coinAmount", "cashAmount", "coinBalance", "maxCoinPerOrder",
                        "coinCapped", "strictestReturnPolicy", "shippingMethod");
    }

    @Test
    @DisplayName("CheckoutQuoteView 必含 coupons / quoteToken / couriers")
    void newViewCarriesNewCapabilities() {
        assertThat(Arrays.stream(CheckoutQuoteView.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .contains("coupons", "quoteToken", "couriers");
    }

    private static PricingResult pricing(long fee, long discount, long payable, long coin,
            boolean capped) {
        return new PricingResult(100_000L, 0L, 100_000L, fee, discount,
                discount > 0 ? ShippingDiscountSource.FREE_SHIPPING_RULE : ShippingDiscountSource.NONE,
                0L, payable, coin, payable - coin, capped, discount, null, null, null, false, true,
                List.of());
    }

    private static CheckoutComputation computation(QuoteOutcome q, PricingResult pr) {
        CartView cart = new CartView(List.of(), List.of(), 100_000L, 100_000L, 1, 1);
        CheckoutInputs in = new CheckoutInputs(CheckoutPreviewRequest.Source.CART, cart,
                List.of(new ResolvedCheckoutLine("s1", "p1", "Produk", "3 kg", 100_000L, 1, null,
                        ReturnPolicy.NO_RETURN_AFTER_OPEN, null, 10L, null, null, null, null)),
                null, List.of(), Map.of(), new FreeShippingRule(0L, null), 50, true, true,
                1_000_000L, 7_000L);
        return new CheckoutComputation(in, q, null, false, false, true, pr, null, NOW, null, null);
    }

    @Test
    @DisplayName("有报价：shippingDiscount 取负（旧口径 ≤ 0），其余金额照搬计价器")
    void shippingDiscountIsNegated() {
        QuoteOutcome ok = new QuoteOutcome(QuoteOutcome.Status.OK, new QuoteResult("q1", true,
                List.of(), 10_000L, "12160", "h", NOW));
        var p = new LegacyCheckoutAdapter().toLegacyPreview(computation(ok,
                pricing(10_000L, 10_000L, 100_000L, 7_000L, false)));

        assertThat(p.shippingFee()).isEqualTo(10_000L);
        assertThat(p.shippingDiscount()).isEqualTo(-10_000L);
        assertThat(p.payableTotal()).isEqualTo(100_000L);
        assertThat(p.coinAmount()).isEqualTo(7_000L);
        assertThat(p.cashAmount()).isEqualTo(93_000L);
        assertThat(p.coinBalance()).isEqualTo(7_000L);
        assertThat(p.maxCoinPerOrder()).isEqualTo(1_000_000L);
        assertThat(p.serviceable()).isTrue();
        assertThat(p.returnPolicies()).containsEntry("s1", ReturnPolicy.NO_RETURN_AFTER_OPEN);
    }

    @Test
    @DisplayName("无地址 / 询价失败：金额全 null、serviceable=true；超范围：金额全 null、serviceable=false")
    void noQuoteShapes() {
        for (QuoteOutcome.Status s : List.of(QuoteOutcome.Status.NO_ADDRESS,
                QuoteOutcome.Status.QUOTE_FAILED, QuoteOutcome.Status.SKIPPED)) {
            var p = new LegacyCheckoutAdapter().toLegacyPreview(computation(
                    new QuoteOutcome(s, null), pricing(0L, 0L, 100_000L, 7_000L, true)));
            assertThat(p.shippingFee()).isNull();
            assertThat(p.shippingDiscount()).isNull();
            assertThat(p.payableTotal()).isNull();
            assertThat(p.coinAmount()).isNull();
            assertThat(p.cashAmount()).isNull();
            assertThat(p.coinCapped()).isFalse();
            assertThat(p.serviceable()).as(s.name()).isTrue();
        }
        var out = new LegacyCheckoutAdapter().toLegacyPreview(computation(
                new QuoteOutcome(QuoteOutcome.Status.NOT_SERVICEABLE, null),
                pricing(0L, 0L, 100_000L, 0L, false)));
        assertThat(out.serviceable()).isFalse();
        assertThat(out.payableTotal()).isNull();
    }

    @Test
    @DisplayName("旧结算入参：CART · 最便宜 · 不用券 · useCoin · 不保价；无 token 不回落默认地址")
    void previewCommand() {
        var cmd = new LegacyCheckoutAdapter().previewCommand(" ");
        assertThat(cmd.request().sourceValue()).isEqualTo(CheckoutPreviewRequest.Source.CART);
        assertThat(cmd.request().courierKey()).isNull();
        assertThat(cmd.request().insuranceValue()).isFalse();
        assertThat(cmd.request().useCoinValue()).isTrue();
        assertThat(cmd.request().addressToken()).isNull();
        assertThat(cmd.couponsEnabled()).isFalse();
        assertThat(cmd.defaultAddressFallback()).isFalse();
    }
}
