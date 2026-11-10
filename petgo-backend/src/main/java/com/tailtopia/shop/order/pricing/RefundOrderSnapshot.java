package com.tailtopia.shop.order.pricing;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.util.List;

/**
 * 退款计算器的输入快照（v1.4.2 shop Story 4.1 AC2.1）。全部取自订单头已有列，<b>不新增同义列</b>。
 *
 * <p>记号（与 story AC 一致）：{@code T = total}、{@code C = coin}、{@code RT = refundedTotal}、
 * {@code RC = refundedCoin}、{@code G = goods}、{@code D = discount}、{@code F = shippingFee}、
 * {@code S = shippingDiscount}（<b>正数</b>减免额，库内 {@code shipping_discount} 为其相反数）、{@code I = insuranceFee}。
 *
 * @param lines 按行 id 升序的订单行（{@code refundedQty} 为截至本次之前已取消 / 退回的件数）
 */
public record RefundOrderSnapshot(long total, long coin, long refundedTotal, long refundedCoin,
        long goods, long discount, long shippingFee, long shippingDiscount, long insuranceFee,
        boolean insuranceSelected, List<RefundLine> lines) {

    public RefundOrderSnapshot {
        lines = List.copyOf(lines);
    }

    /** 订单行快照：{@code discountEligible} = 下单时是否落在满减券适用范围（3.2 快照列）。 */
    public record RefundLine(long lineId, long unitPrice, int qty, int refundedQty, boolean discountEligible) {
    }

    /**
     * 由订单头 + 行装配。存量订单：{@code coin_amount} 为空 → 0；{@code discount_coupon_amount} /
     * {@code insurance_fee} 列默认 0；{@code discount_eligible} 默认 false。
     */
    public static RefundOrderSnapshot of(ShopOrder order, List<ShopOrderLine> lines) {
        return new RefundOrderSnapshot(
                order.getTotalAmount(),
                order.getCoinAmount() == null ? 0L : order.getCoinAmount(),
                order.getRefundedTotal(),
                order.getRefundedCoin(),
                order.getGoodsSubtotal(),
                order.getDiscountCouponAmount(),
                order.getShippingFee(),
                Math.negateExact(order.getShippingDiscount()),
                order.getInsuranceFee(),
                order.isInsuranceSelected(),
                lines.stream()
                        .map(l -> new RefundLine(l.getId(), l.getUnitPrice(), l.getQty(), l.getRefundedQty(),
                                l.isDiscountEligible()))
                        .toList());
    }

    /** {@code E}：满减适用商品金额 = Σ(单价 × <b>下单数量</b>)，只算 {@code discountEligible} 行。 */
    public long eligibleAmount() {
        long e = 0L;
        for (RefundLine l : lines) {
            if (l.discountEligible()) {
                e = Math.addExact(e, Math.multiplyExact(l.unitPrice(), (long) l.qty()));
            }
        }
        return e;
    }

    /** 商品实付 {@code G − D}。 */
    public long goodsPaid() {
        return Math.subtractExact(goods, discount);
    }

    /** 实付运费 {@code F − S}（下限 0）。 */
    public long shippingPaid() {
        return Math.max(0L, Math.subtractExact(shippingFee, shippingDiscount));
    }

    /** 剩余未退应付 {@code T − RT}。 */
    public long remaining() {
        return Math.subtractExact(total, refundedTotal);
    }

    /** 剩余未退币 {@code C − RC}（下限 0）。 */
    public long remainingCoin() {
        return Math.max(0L, Math.subtractExact(coin, refundedCoin));
    }

    /** 全单剩余未取消件数。 */
    public long remainingQty() {
        long n = 0L;
        for (RefundLine l : lines) {
            n += Math.max(0, l.qty() - l.refundedQty());
        }
        return n;
    }
}
