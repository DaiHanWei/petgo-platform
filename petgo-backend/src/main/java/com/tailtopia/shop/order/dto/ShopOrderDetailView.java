package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        List<Package> packages,
        /**
         * 🔴 <b>整单归因来源</b>（Story 9.2）：供客户端 {@code toko_order_payment_succeeded}
         * 携带 {@code attribution_source}，与服务端行级归因<b>互为校验</b>。
         *
         * <p>⚠️ <b>权威值始终在服务端</b>（{@code shop_order_lines.entry_source}）。
         * 客户端事件会被广告拦截与丢包吃掉；两套一比就知道端上丢了多少，
         * 偏差过大即说明客户端埋点有丢失，<b>以服务端为准</b>。
         *
         * <p>取值见 {@link #attributionSourceOf(List)}。
         */
        String attributionSource) {

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

    /**
     * 订单行。{@code returnPolicy} 是下单时定格的承诺（FR-104，退货时按它执行）。
     *
     * <p>{@code mainImageUrl}：商品主图 CDN 全 URL，2026-09-03 追加。无图为 null，端上走占位图。
     * ⚠️ 它是**读时派生**的（见 {@code ShopLineImageResolver}），不是下单快照 ——
     * 运营事后换主图，这里会跟着变。用途是「认出是哪件」，不是留证。
     */
    public record Line(String productName, String specName, long unitPrice, int qty,
            long lineTotal, String returnPolicy, String mainImageUrl) {
    }

    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            Map<Long, String> imageUrlBySkuId) {
        return of(o, lines, List.of(), imageUrlBySkuId);
    }

    /**
     * @param imageUrlBySkuId skuId → 主图 URL，由 {@code ShopLineImageResolver} 批量取。
     *     🔴 <b>刻意做成必传</b>：给个「不带图」的重载，下一个人照着写就又是一页占位图 ——
     *     下单后全链路无图（2026-09-03 stag 回归 P2）正是这么来的。没有图就传 {@code Map.of()}，
     *     那是一个显式的选择。
     */
    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments, Map<Long, String> imageUrlBySkuId) {
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
                                l.getReturnPolicy() == null ? null : l.getReturnPolicy().name(),
                                imageUrlBySkuId.get(l.getSkuId())))
                        .toList(),
                o.getShippedAt(),
                o.getDeliveredAt(),
                o.getCompletedAt(),
                o.returnWindowEndsAt(),
                shipments.stream()
                        .map(s -> new Package(s.getCarrier().name(), s.getCarrier().displayName(),
                                s.getTrackingNo(), s.getCarrier().trackingUrl(),
                                s.getStatus().name(), s.getShippedAt(), s.getDeliveredAt()))
                        .toList(),
                attributionSourceOf(lines));
    }

    /**
     * 整单归因来源：<b>全部行同源取该源；多源取 {@code mixed}；无源取 {@code unknown}</b>。
     *
     * <p>🔴 <b>多源不能挑第一条充数</b>：一单里既有复购卡进来的、又有自己逛进来的，
     * 报成其中任一个都会让 AB-13B 的分子分母各错一次 —— 而 AB-13B 是裁决
     * A-16（复购引擎是否成立）的唯一依据。宁可标成 {@code mixed} 让它落到单独一档。
     *
     * <p>🔒 出参恒为受控词表值，不含 PII。
     */
    public static String attributionSourceOf(List<ShopOrderLine> lines) {
        String seen = null;
        for (ShopOrderLine l : lines) {
            String src = l.getEntrySource();
            if (src == null || src.isBlank()) {
                continue;
            }
            if (seen == null) {
                seen = src;
            } else if (!seen.equals(src)) {
                return "mixed";
            }
        }
        return seen == null ? "unknown" : seen;
    }
}
