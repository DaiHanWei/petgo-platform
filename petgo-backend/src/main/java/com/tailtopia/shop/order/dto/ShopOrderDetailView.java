package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.time.Instant;
import java.util.List;

/**
 * 电商订单详情（Story 3.8 待支付态 · Story 4.1 履约态）。
 *
 * <p>🔴 <b>{@code expiresAt} 是服务端时刻，客户端倒计时只据此渲染</b>（AD-8）——
 * 用客户端本地计时判定，改一下手机时间就能无限延长锁库存的时间。
 *
 * <p>🔴 三段金额分开下发：{@code coinAmount} / {@code cashAmount} 为 null 表示非混合支付。
 *
 * <p>🔴 <b>Story 4.1 只加字段、不改既有字段语义</b>（Epic 3 交接约定）：
 * {@code deliveredAt} 即「签收」时刻（SPEC-5），{@code returnWindowEndsAt} 由它 +7 日算出、
 * 服务端下发 —— 让 App 自己加 7 天，时区与设备时钟一错，用户就会看到一个和后端不一致的截止日。
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
        List<Line> lines,
        // ---------- Story 4.1 履约态 ----------
        Instant shippedAt,
        /** 🔴 「签收」时刻（SPEC-5）。退货窗口以此起算。 */
        Instant deliveredAt,
        Instant completedAt,
        /** 退货窗口截止（签收 +7 日）。未签收为 null。 */
        Instant returnWindowEndsAt,
        /** S-2 一单多包：逐条列出，各自标明送达状态。 */
        List<Package> packages) {

    /**
     * 包裹（S-2）。
     *
     * <p>🔴 <b>{@code trackingUrl} 是承运商官网地址，App 只做跳转</b> ——
     * 不接承运商 API、不在 App 内渲染物流轨迹（FR-103）。
     */
    public record Package(String carrier, String carrierName, String trackingNo,
            String trackingUrl, String status, Instant shippedAt, Instant deliveredAt) {
    }

    /** 收货信息快照（下单时定格，不随地址簿改动）。🔒 含 PII，只下发给订单主人。 */
    public record ShipTo(String receiverName, String receiverPhone, String provinsi,
            String kotaKabupaten, String kecamatan, String addressLine, String kodePos) {
    }

    /** 订单行。{@code returnPolicy} 是下单时定格的承诺（FR-104，退货时按它执行）。 */
    public record Line(String productName, String specName, long unitPrice, int qty,
            long lineTotal, String returnPolicy) {
    }

    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines) {
        return of(o, lines, List.of());
    }

    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments) {
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
                        .toList(),
                o.getShippedAt(),
                o.getDeliveredAt(),
                o.getCompletedAt(),
                o.returnWindowEndsAt(),
                shipments.stream()
                        .map(s -> new Package(s.getCarrier().name(), s.getCarrier().displayName(),
                                s.getTrackingNo(), s.getCarrier().trackingUrl(),
                                s.getStatus().name(), s.getShippedAt(), s.getDeliveredAt()))
                        .toList());
    }
}
