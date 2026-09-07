package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.order.domain.ShopOrder;
import java.time.Instant;

/**
 * 电商订单对外视图（Story 3.7 下单响应；3.8/3.9 复用）。
 *
 * <p>🔴 只暴露不可枚举 {@code orderToken}，<b>绝不下发自增 id 或 seq_no</b>（NFR-3 · CLAUDE.md 护栏）。
 * {@code seq_no} 是运营对账用的连续号，外露即可枚举全平台订单量。
 *
 * <p>🔴 <b>三段金额分开下发</b>：{@code coinAmount} / {@code cashAmount} 为 null 表示该单不是混合支付
 * （纯 QRIS 或纯 Coin，见 AD-1），前端据此决定只显示一段还是两段。
 */
public record ShopOrderView(
        String orderToken,
        String status,
        long goodsSubtotal,
        long shippingFee,
        long shippingDiscount,
        long totalAmount,
        String payChannel,
        Long coinAmount,
        Long cashAmount,
        Instant createdAt) {

    public static ShopOrderView of(ShopOrder o) {
        return new ShopOrderView(
                o.getPublicToken(),
                o.getStatus().name(),
                o.getGoodsSubtotal(),
                o.getShippingFee(),
                o.getShippingDiscount(),
                o.getTotalAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCoinAmount(),
                o.getCashAmount(),
                o.getCreatedAt());
    }
}
