package com.tailtopia.admin.shop.service;

/**
 * 对账「优惠与物流成本」的派生列（V1.4.2 shop-admin Story 8.1 · AD-15）。纯函数，单测钉住。
 *
 * <p>🔴 {@code shop_orders.shipping_discount} 存<b>负数或 0</b>（{@code total = goods − coupon + shipping_fee +
 * shipping_discount + insurance_fee}）：用户实付运费 = {@code shipping_fee + shipping_discount}。
 * 运费净成本 = Biteship 实际运费 + 重发运费 − 用户实付运费 —— 满额免运 / 免运券减免<b>已含在</b>用户实付运费里，不再另加。
 */
public final class LogisticsCostCalc {

    private LogisticsCostCalc() {
    }

    /** 用户实付运费（单笔或合计都成立：线性）。 */
    public static long userPaidShipping(long shippingFee, long shippingDiscount) {
        return shippingFee + shippingDiscount;
    }

    /** 运费净成本（可为负：用户付的运费比实际成本多）。 */
    public static long netShippingCost(long actualCarrierCost, long reshipCost, long userPaidShipping) {
        return actualCarrierCost + reshipCost - userPaidShipping;
    }
}
