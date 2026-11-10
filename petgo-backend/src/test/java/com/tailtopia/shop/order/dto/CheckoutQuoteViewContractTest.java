package com.tailtopia.shop.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.shop.order.pricing.FreeShippingProgress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约：新结算视图 JSON 键名（v1.4.2 shop Story 3.1 AC1.9）。纯 Jackson、镜像生产 NON_NULL。
 */
class CheckoutQuoteViewContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static CheckoutQuoteView full() {
        CheckoutQuoteView.CouponOption opt = new CheckoutQuoteView.CouponOption("uc1", "tpl1",
                "DISCOUNT", "Diskon", "Discount", 5_000L, 0L, Instant.parse("2026-11-12T00:00:00Z"),
                true, 5_000L, null);
        return new CheckoutQuoteView("CART", null, "q1",
                List.of(new CheckoutQuoteView.CourierOption("jne:reg", "jne", "reg", "JNE",
                        "Reguler", "2 - 3", 10_000L, true, true)),
                "jne:reg", true, false,
                List.of(new CheckoutQuoteView.QuoteLine("s1", "p1", "Produk", "3 kg", 100_000L, 1,
                        "https://cdn/p.jpg", "RETURNABLE", null, 10L)),
                new CheckoutQuoteView.PricingView(100_000L, 5_000L, 10_000L, 0L, "NONE", 0L,
                        105_000L, 0L, 105_000L, 5_000L, true, new FreeShippingProgress(300_000L,
                                205_000L)),
                new CheckoutQuoteView.CouponGroups(
                        new CheckoutQuoteView.CouponGroup("uc1", List.of(opt), List.of()),
                        new CheckoutQuoteView.CouponGroup(null, List.of(), List.of())),
                new CheckoutQuoteView.CoinView(5_000L, 1_000_000L, true, true, false),
                "RETURNABLE", "RETURNABLE", true, null);
    }

    @Test
    @DisplayName("顶层与嵌套键名：quoteToken / couriers[].courierKey / pricing.shippingIncluded / coupons.discount.available / coin.capReached")
    @SuppressWarnings("unchecked")
    void keyNames() {
        Map<String, Object> m = wire(full());
        assertThat(m).containsKeys("source", "quoteToken", "couriers", "selectedCourierKey",
                "insuranceAvailable", "insurance", "lines", "pricing", "coupons", "coin",
                "strictestReturnPolicy", "returnPolicyCopyKey", "placeable");
        assertThat(((List<Map<String, Object>>) m.get("couriers")).getFirst())
                .containsKeys("courierKey", "courierCode", "serviceCode", "courierName",
                        "serviceName", "etaText", "price", "insuranceSupported", "cheapest");
        assertThat((Map<String, Object>) m.get("pricing")).containsKeys("goodsAmount",
                "discountCouponAmount", "shippingFee", "shippingDiscount", "shippingDiscountSource",
                "insuranceFee", "payable", "coinAmount", "cashAmount", "savedAmount",
                "shippingIncluded", "freeShippingProgress");
        Map<String, Object> coupons = (Map<String, Object>) m.get("coupons");
        Map<String, Object> discount = (Map<String, Object>) coupons.get("discount");
        assertThat(discount).containsKeys("selectedToken", "available", "unavailable");
        assertThat(coupons).containsKey("freeShipping");
        assertThat(((List<Map<String, Object>>) discount.get("available")).getFirst())
                .containsKeys("userCouponToken", "couponToken", "kind", "nameId", "nameEn",
                        "amount", "minSpend", "validUntil", "expiringSoon", "deduction");
        assertThat((Map<String, Object>) m.get("coin")).containsKeys("balance", "cap", "enabled",
                "useCoin", "capReached");
        assertThat(((List<Map<String, Object>>) m.get("lines")).getFirst())
                .containsKeys("skuToken", "productToken", "productName", "specName", "price",
                        "qty", "mainImageUrl", "returnPolicy", "availableStock");
    }

    @Test
    @DisplayName("NON_NULL：null 字段省略（address / notPlaceableReason / 未选券 selectedToken）")
    @SuppressWarnings("unchecked")
    void nullsAreOmitted() {
        Map<String, Object> m = wire(full());
        assertThat(m).doesNotContainKey("address").doesNotContainKey("notPlaceableReason");
        Map<String, Object> fs = (Map<String, Object>) ((Map<String, Object>) m.get("coupons"))
                .get("freeShipping");
        assertThat(fs).doesNotContainKey("selectedToken");
    }

    @Test
    @DisplayName("notPlaceableReason 有值时下发")
    void notPlaceableReasonPresent() {
        CheckoutQuoteView f = full();
        CheckoutQuoteView v = new CheckoutQuoteView(f.source(), null, null, List.of(), null, false,
                false, f.lines(), f.pricing(), f.coupons(), f.coin(), "RETURNABLE", "RETURNABLE",
                false, CheckoutQuoteView.NO_ADDRESS);
        assertThat(wire(v)).containsEntry("notPlaceableReason", "NO_ADDRESS")
                .containsEntry("placeable", false)
                .doesNotContainKey("quoteToken");
    }
}
