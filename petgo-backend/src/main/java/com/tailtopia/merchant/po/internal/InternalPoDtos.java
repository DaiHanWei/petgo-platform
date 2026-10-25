package com.tailtopia.merchant.po.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderLine;
import com.tailtopia.merchant.po.domain.PurchaseOrderShipment;
import com.tailtopia.merchant.po.service.PurchaseOrderPaymentService.PoPayment;
import com.tailtopia.merchant.po.service.PurchaseOrderService;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * 内部接口报文（契约 5.3～5.6）。键名 snake_case（逐字段 {@code @JsonProperty}，App API 的 camelCase 全局配置不动）；
 * 可空字段为 null 时按全局 {@code non_null} 省略 —— 契约 5.1 规定「显式 null 与省略等价」。
 * 🔒 地址、门店电话、QRIS 内容是 PII / 支付凭据，这些 record 不得被打进日志。
 */
final class InternalPoDtos {

    private InternalPoDtos() {
    }

    // ---------- 请求（接口 1） ----------

    record CreateRequest(
            @JsonProperty("lines") List<LineRequest> lines,
            @JsonProperty("shipping_address") AddressDto shippingAddress,
            @JsonProperty("shipping_method") String shippingMethod,
            @JsonProperty("store_snapshot") StoreSnapshotRequest storeSnapshot) {
    }

    record LineRequest(
            @JsonProperty("sku_token") String skuToken,
            @JsonProperty("qty") Integer qty,
            @JsonProperty("wholesale_price") Long wholesalePrice) {
    }

    record StoreSnapshotRequest(
            @JsonProperty("name") String name,
            @JsonProperty("phone") String phone) {
    }

    record AddressDto(
            @JsonProperty("receiver_name") String receiverName,
            @JsonProperty("receiver_phone") String receiverPhone,
            @JsonProperty("provinsi") String provinsi,
            @JsonProperty("kota_kabupaten") String kotaKabupaten,
            @JsonProperty("kecamatan") String kecamatan,
            @JsonProperty("address_line") String addressLine,
            @JsonProperty("kode_pos") String kodePos) {

        static AddressDto of(AddressSnapshot a) {
            return new AddressDto(a.receiverName(), a.receiverPhone(), a.provinsi(), a.kotaKabupaten(),
                    a.kecamatan(), a.addressLine(), a.kodePos());
        }

        @Override
        public String toString() {
            return "AddressDto[PII omitted]";
        }
    }

    // ---------- 响应 ----------

    /** 契约 5.3 {@code PurchaseOrder}。 */
    record PurchaseOrderDto(
            @JsonProperty("po_token") String poToken,
            @JsonProperty("order_no") String orderNo,
            @JsonProperty("status") String status,
            @JsonProperty("cancel_reason") String cancelReason,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("payment_deadline") Instant paymentDeadline,
            @JsonProperty("paid_at") Instant paidAt,
            @JsonProperty("shipped_at") Instant shippedAt,
            @JsonProperty("delivered_at") Instant deliveredAt,
            @JsonProperty("completed_at") Instant completedAt,
            @JsonProperty("cancelled_at") Instant cancelledAt,
            @JsonProperty("lines") List<LineDto> lines,
            @JsonProperty("goods_amount") long goodsAmount,
            @JsonProperty("shipping_method") String shippingMethod,
            @JsonProperty("shipping_address") AddressDto shippingAddress,
            @JsonProperty("shipments") List<ShipmentDto> shipments) {

        static PurchaseOrderDto of(PurchaseOrderService.PurchaseOrderView v) {
            PurchaseOrder o = v.order();
            return new PurchaseOrderDto(o.getPublicToken(), o.getOrderNo(), o.getStatus().name(),
                    o.getCancelReason() == null ? null : o.getCancelReason().name(),
                    o.getCreatedAt(), o.getPaymentDeadline(), o.getPaidAt(), o.getShippedAt(),
                    o.getDeliveredAt(), o.getCompletedAt(), o.getCancelledAt(),
                    v.lines().stream().map(LineDto::of).toList(), o.getGoodsAmount(),
                    o.getShippingMethod().name(), AddressDto.of(o.shipTo()),
                    v.shipments().stream().map(ShipmentDto::of).toList());
        }
    }

    record LineDto(
            @JsonProperty("sku_token") String skuToken,
            @JsonProperty("product_name") String productName,
            @JsonProperty("spec_name") String specName,
            @JsonProperty("image_key") String imageKey,
            @JsonProperty("wholesale_price") long wholesalePrice,
            @JsonProperty("qty") int qty,
            @JsonProperty("subtotal") long subtotal) {

        static LineDto of(PurchaseOrderLine l) {
            return new LineDto(l.getSkuToken(), l.getProductName(), l.getSpecName(), l.getImageKey(),
                    l.getWholesalePrice(), l.getQty(), l.getSubtotal());
        }
    }

    record ShipmentDto(
            @JsonProperty("carrier") String carrier,
            @JsonProperty("tracking_no") String trackingNo,
            @JsonProperty("tracking_url") String trackingUrl,
            @JsonProperty("shipped_at") Instant shippedAt) {

        static ShipmentDto of(PurchaseOrderShipment s) {
            return new ShipmentDto(s.getCarrier(), s.getTrackingNo(), s.getTrackingUrl(), s.getShippedAt());
        }
    }

    /** 契约 5.5 {@code Payment}。 */
    record PaymentDto(
            @JsonProperty("po_token") String poToken,
            @JsonProperty("qris_content") String qrisContent,
            @JsonProperty("amount") long amount,
            @JsonProperty("expires_at") Instant expiresAt) {

        static PaymentDto of(PoPayment p) {
            return new PaymentDto(p.poToken(), p.qrisContent(), p.amount(), p.expiresAt());
        }
    }

    /** 契约 5.6 接口 4 响应。 */
    record PageDto(
            @JsonProperty("items") List<RowDto> items,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {

        static PageDto of(PurchaseOrderService.PageView p) {
            return new PageDto(p.items().stream().map(RowDto::of).toList(), p.page(), p.size(),
                    p.totalElements(), p.totalPages());
        }
    }

    record RowDto(
            @JsonProperty("po_token") String poToken,
            @JsonProperty("order_no") String orderNo,
            @JsonProperty("status") String status,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("goods_amount") long goodsAmount,
            @JsonProperty("shipping_method") String shippingMethod,
            @JsonProperty("first_line") FirstLineDto firstLine,
            @JsonProperty("line_count") long lineCount) {

        static RowDto of(PurchaseOrderService.RowView r) {
            PurchaseOrder o = r.order();
            PurchaseOrderLine f = r.firstLine();
            return new RowDto(o.getPublicToken(), o.getOrderNo(), o.getStatus().name(), o.getCreatedAt(),
                    o.getGoodsAmount(), o.getShippingMethod().name(),
                    new FirstLineDto(f.getSkuToken(), f.getProductName(), f.getSpecName(), f.getImageKey()),
                    r.lineCount());
        }
    }

    record FirstLineDto(
            @JsonProperty("sku_token") String skuToken,
            @JsonProperty("product_name") String productName,
            @JsonProperty("spec_name") String specName,
            @JsonProperty("image_key") String imageKey) {
    }

    /** 契约 5.6 接口 5 响应。 */
    record SummaryDto(
            @JsonProperty("paid_count") long paidCount,
            @JsonProperty("paid_goods_amount") long paidGoodsAmount) {
    }
}
