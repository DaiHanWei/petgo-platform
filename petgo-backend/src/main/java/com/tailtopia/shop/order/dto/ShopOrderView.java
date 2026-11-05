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
 *
 * <p>v1.4.2 shop Story 3.2 末尾追加 {@code discountCouponAmount} / {@code insuranceFee} / {@code paid}。
 * {@code paid} = 状态已离开 {@code PENDING_PAYMENT} 且不是 {@code CANCELLED}（未付款取消的单从未付款；
 * 幂等重放可能取回一张已取消的单，不能让 App 因此进支付成功页）。
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
        Instant createdAt,
        // ---- v1.4.2 shop Story 3.2：只在末尾追加 ----
        long discountCouponAmount,
        long insuranceFee,
        boolean paid) {

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
                o.getCreatedAt(),
                o.getDiscountCouponAmount(),
                o.getInsuranceFee(),
                // 已离开待支付（纯币 / 零元单提交即结清）→ App 直接进支付成功页、不拉支付面板（FR-128.7）
                o.getStatus() != com.tailtopia.shop.order.domain.ShopOrderStatus.PENDING_PAYMENT
                        && o.getStatus() != com.tailtopia.shop.order.domain.ShopOrderStatus.CANCELLED);
    }
}
