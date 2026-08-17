package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.time.Instant;
import java.util.List;

/**
 * 电商订单详情（Story 3.8 待支付态；Epic 4 的履约态复用同一视图，届时只加字段不改语义）。
 *
 * <p>🔴 <b>{@code expiresAt} 是服务端时刻，客户端倒计时只据此渲染</b>（AD-8）——
 * 用客户端本地计时判定，改一下手机时间就能无限延长锁库存的时间。
 *
 * <p>🔴 三段金额分开下发：{@code coinAmount} / {@code cashAmount} 为 null 表示非混合支付。
 */
public record ShopOrderDetailView(
        String orderToken,
        String status,
        long goodsSubtotal,
        long shippingFee,
        long shippingDiscount,
        long totalAmount,
        String payChannel,
        Long coinAmount,
        Long cashAmount,
        Instant expiresAt,
        Instant createdAt,
        String paymentIntentToken,
        ShipTo shipTo,
        List<Line> lines) {

    /** 收货信息快照（下单时定格，不随地址簿改动）。🔒 含 PII，只下发给订单主人。 */
    public record ShipTo(String receiverName, String receiverPhone, String provinsi,
            String kotaKabupaten, String kecamatan, String addressLine, String kodePos) {
    }

    /** 订单行。{@code returnPolicy} 是下单时定格的承诺（FR-104，退货时按它执行）。 */
    public record Line(String productName, String specName, long unitPrice, int qty,
            long lineTotal, String returnPolicy) {
    }

    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines) {
        var ship = o.shipTo();
        return new ShopOrderDetailView(
                o.getPublicToken(),
                o.getStatus().name(),
                o.getGoodsSubtotal(),
                o.getShippingFee(),
                o.getShippingDiscount(),
                o.getTotalAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCoinAmount(),
                o.getCashAmount(),
                o.getExpiresAt(),
                o.getCreatedAt(),
                o.getPaymentIntentToken(),
                new ShipTo(ship.receiverName(), ship.receiverPhone(), ship.provinsi(),
                        ship.kotaKabupaten(), ship.kecamatan(), ship.addressLine(),
                        ship.kodePos()),
                lines.stream()
                        .map(l -> new Line(l.getProductName(), l.getSpecName(), l.getUnitPrice(),
                                l.getQty(), l.getLineTotal(),
                                l.getReturnPolicy() == null ? null : l.getReturnPolicy().name()))
                        .toList());
    }
}
