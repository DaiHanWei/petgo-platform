package com.tailtopia.shop.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约：下单响应 {@link ShopOrderView}（v1.4.2 shop Story 3.2 AC3.4）—— 旧键与顺序不变，
 * 只在末尾追加 {@code discountCouponAmount} / {@code insuranceFee} / {@code paid}。
 */
class ShopOrderViewContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final AddressSnapshot SHIP = new AddressSnapshot("B", "+62", "P", "K", "Kec",
            "Jl", "12160");

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private static ShopOrder order(long coin, long cash) {
        ShopOrder o = ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot("ord1", "TOKO-1",
                Instant.parse("2026-11-05T03:00:00Z"), 9L, SHIP, null, CheckoutSource.CART,
                200_000L, 50_000L, 20_000L, 5_000L, ShippingDiscountSource.COUPON, 750L, true,
                165_750L, 1L, 2L, "jne", "reg", "JNE", "REG", "2 - 3", 7L, coin > 0));
        ReflectionTestUtils.setField(o, "id", 1L);
        o.applyPaymentSplit(coin > 0 && cash > 0 ? PayChannel.MIXED
                : coin > 0 ? PayChannel.PAWCOIN : PayChannel.QRIS, coin, cash);
        return o;
    }

    @Test
    @DisplayName("键集合：旧 10 键顺序不变，末尾追加 3 键")
    void keys() {
        assertThat(List.copyOf(wire(ShopOrderView.of(order(0L, 165_750L))).keySet())).containsExactly(
                "orderToken", "status", "goodsSubtotal", "shippingFee", "shippingDiscount",
                "totalAmount", "payChannel", "coinAmount", "cashAmount", "createdAt",
                "discountCouponAmount", "insuranceFee", "paid");
    }

    @Test
    @DisplayName("金额照订单头原样下发：shippingDiscount 为负、totalAmount = 应付")
    void amounts() {
        Map<String, Object> w = wire(ShopOrderView.of(order(65_750L, 100_000L)));
        assertThat(((Number) w.get("goodsSubtotal")).longValue()).isEqualTo(200_000L);
        assertThat(((Number) w.get("shippingDiscount")).longValue()).isEqualTo(-5_000L);
        assertThat(((Number) w.get("discountCouponAmount")).longValue()).isEqualTo(50_000L);
        assertThat(((Number) w.get("insuranceFee")).longValue()).isEqualTo(750L);
        assertThat(((Number) w.get("totalAmount")).longValue()).isEqualTo(165_750L);
        assertThat(w.get("payChannel")).isEqualTo("MIXED");
        assertThat(w.get("paid")).isEqualTo(false);
    }

    @Test
    @DisplayName("paid：待支付 false；已结清 true；未付款取消 false")
    void paidFlag() {
        ShopOrder pending = order(0L, 165_750L);
        assertThat(ShopOrderView.of(pending).paid()).isFalse();

        ShopOrder settled = order(165_750L, 0L);
        settled.markPaid(Instant.now());
        assertThat(ShopOrderView.of(settled).paid()).isTrue();

        ShopOrder cancelled = order(0L, 165_750L);
        cancelled.markCancelled(com.tailtopia.shop.order.refund.CancelReason.USER_UNPAID_CANCEL,
                Instant.now());
        assertThat(ShopOrderView.of(cancelled).paid()).isFalse();
    }
}
