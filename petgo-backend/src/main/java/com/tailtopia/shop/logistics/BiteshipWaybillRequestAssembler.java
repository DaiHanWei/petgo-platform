package com.tailtopia.shop.logistics;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest.Contact;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest.Item;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 下运单请求组装（v1.4.2 shop Story 5.1 AC4.2）。<b>纯函数</b>，不读库、不出网。
 *
 * <ul>
 *   <li>origin：发货地四项（1.1 {@code shipping_settings}），任一为空 → 失败 {@code ORIGIN_NOT_CONFIGURED}；</li>
 *   <li>destination：订单收件快照；电话只留数字（{@code +62812…} → {@code 62812…}）；地址
 *       {@code address_line, kecamatan, kota_kabupaten, provinsi} 逗号拼接；邮编 = {@code ship_kode_pos}；</li>
 *   <li>items：逐行 {@code qty − refunded_qty}（部分取消的件不发；剩 0 的行跳过；全为 0 → 409）；
 *       包裹参数取 SKU 当前值，任一缺失 → 失败 {@code SKU_PACKAGE_MISSING}；</li>
 *   <li>{@code courier_insurance}：勾选保价时 = 商品实付 = {@code goods_subtotal − discount_coupon_amount − LINE 级退款
 *       goods_refund 之和}，下限 0；未勾选不传；</li>
 *   <li>{@code reference_id = display_no + "-" + attempt}（attempt 为本次 +1 后的 {@code waybill_attempts}）。</li>
 * </ul>
 *
 * <p>🔒 产物含 PII，调用方不得整体记日志。
 */
public final class BiteshipWaybillRequestAssembler {

    public static final String ORIGIN_NOT_CONFIGURED = "ORIGIN_NOT_CONFIGURED";
    public static final String SKU_PACKAGE_MISSING = "SKU_PACKAGE_MISSING";

    private BiteshipWaybillRequestAssembler() {
    }

    /** 发货地（{@code shipping_settings} 1.1 列）。 */
    public record Origin(String contactName, String contactPhone, String address, String postalCode) {

        boolean complete() {
            return notBlank(contactName) && notBlank(contactPhone) && notBlank(address) && notBlank(postalCode);
        }

        @Override
        public String toString() {
            return "Origin{***}";
        }
    }

    /** SKU 当前包裹参数（任一为 null / 非正即缺失）。 */
    public record SkuPackage(Integer weightG, Integer lengthCm, Integer widthCm, Integer heightCm) {

        boolean complete() {
            return positive(weightG) && positive(lengthCm) && positive(widthCm) && positive(heightCm);
        }
    }

    /** 组装结果：可下单，或数据缺失（记运单失败、不调 Biteship）。 */
    public sealed interface Assembly {
    }

    /** @param insuranceAmount 申报保价额；未勾选为 0 */
    public record Ready(BiteshipCreateOrderRequest request, long insuranceAmount) implements Assembly {
    }

    public record Missing(String reason) implements Assembly {
    }

    /**
     * @param attempt           本次 +1 后的 {@code waybill_attempts}
     * @param lineGoodsRefunded 该单 LINE 级退款单 {@code goods_refund} 之和
     * @throws AppException 409：订单已无可发货商品
     */
    public static Assembly assemble(ShopOrder order, List<ShopOrderLine> lines, Map<Long, SkuPackage> packages,
            Origin origin, long lineGoodsRefunded, int attempt, String deliveryType) {
        List<Item> items = new ArrayList<>();
        boolean packageMissing = false;
        for (ShopOrderLine l : lines) {
            int qty = l.getQty() - l.getRefundedQty();
            if (qty <= 0) {
                continue;
            }
            SkuPackage p = packages.get(l.getSkuId());
            if (p == null || !p.complete()) {
                packageMissing = true;
                continue;
            }
            items.add(new Item(itemName(l), l.getUnitPrice(), qty, p.weightG(), p.lengthCm(), p.widthCm(),
                    p.heightCm()));
        }
        if (items.isEmpty() && !packageMissing) {
            throw AppException.conflict("订单已无可发货商品");
        }
        if (origin == null || !origin.complete()) {
            return new Missing(ORIGIN_NOT_CONFIGURED);
        }
        if (packageMissing) {
            return new Missing(SKU_PACKAGE_MISSING);
        }
        AddressSnapshot ship = order.shipTo();
        Contact dest = new Contact(ship.receiverName(), normalizePhone(ship.receiverPhone()),
                destinationAddress(ship), ship.kodePos());
        Contact from = new Contact(origin.contactName().trim(), normalizePhone(origin.contactPhone()),
                origin.address().trim(), origin.postalCode().trim());
        long insurance = order.isInsuranceSelected()
                ? insuranceValue(order.getGoodsSubtotal(), order.getDiscountCouponAmount(), lineGoodsRefunded) : 0L;
        BiteshipCreateOrderRequest req = new BiteshipCreateOrderRequest(referenceId(order.getDisplayNo(), attempt),
                order.getCourierCode(), order.getCourierServiceCode(), order.getShippingFee(),
                deliveryType == null || deliveryType.isBlank() ? "now" : deliveryType.trim(), from, dest, items,
                order.isInsuranceSelected() ? insurance : null);
        return new Ready(req, insurance);
    }

    /** {@code display_no-attempt}，如 {@code TOKO-20261105-ABC123-1}。 */
    public static String referenceId(String displayNo, int attempt) {
        return displayNo + "-" + attempt;
    }

    /** 只留数字：{@code +62 812-3456} → {@code 628123456}。 */
    public static String normalizePhone(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    /** {@code address_line, kecamatan, kota_kabupaten, provinsi}，跳过空段。 */
    public static String destinationAddress(AddressSnapshot s) {
        return Stream.of(s.addressLine(), s.kecamatan(), s.kotaKabupaten(), s.provinsi())
                .filter(BiteshipWaybillRequestAssembler::notBlank).map(String::trim)
                .collect(Collectors.joining(", "));
    }

    /** 商品实付 = 商品金额 − 满减 − LINE 级已退商品额，下限 0。 */
    public static long insuranceValue(long goodsSubtotal, long discountCouponAmount, long lineGoodsRefunded) {
        return Math.max(0L, goodsSubtotal - discountCouponAmount - lineGoodsRefunded);
    }

    /** 商品名 + 空格 + 规格名，截断 100 字（码点）。 */
    static String itemName(ShopOrderLine l) {
        String name = (l.getProductName() == null ? "" : l.getProductName())
                + (notBlank(l.getSpecName()) ? " " + l.getSpecName() : "");
        name = name.trim();
        return name.codePointCount(0, name.length()) <= 100 ? name
                : name.substring(0, name.offsetByCodePoints(0, 100));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean positive(Integer v) {
        return v != null && v > 0;
    }
}
