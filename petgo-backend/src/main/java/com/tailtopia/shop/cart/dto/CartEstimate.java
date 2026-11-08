package com.tailtopia.shop.cart.dto;

import com.tailtopia.shop.order.pricing.FreeShippingProgress;

/**
 * 购物车底栏预估（v1.4.2 shop Story 3.1 AC3，D-9 / FR-133.8）。
 *
 * <p>口径（PRD 11-04 拍板）：预估应付 = 所选商品金额 + 本单最低运费 − 默认最优券（AUTO）− 满额免运减免；
 * <b>不用币、不保价</b>。全部数字由 {@code ShopPricingCalculator} 以「最便宜快递 + AUTO 券 +
 * useCoin=false + insurance=false」入参算出，这里只搬运。
 *
 * @param lowestFee        本单最低运费；无报价（无收货地 / 不可配送 / 询价失败 / 一件没选）为 null
 * @param shippingDiscount 运费减免（正数，计价器原值）；运费未知为 0
 * @param payable          预估应付；{@code lowestFee == null} 时不含运费
 * @param saved            计价器 {@code savedAmount}
 * @param reason           {@code null | NO_DESTINATION | NOT_SERVICEABLE | QUOTE_FAILED | NO_SELECTION}
 */
public record CartEstimate(
        Long lowestFee,
        long goodsAmount,
        long discountCouponAmount,
        long shippingDiscount,
        long payable,
        long saved,
        FreeShippingProgress freeShippingProgress,
        String reason) {

    public static final String NO_DESTINATION = "NO_DESTINATION";
    public static final String NOT_SERVICEABLE = "NOT_SERVICEABLE";
    public static final String QUOTE_FAILED = "QUOTE_FAILED";
    public static final String NO_SELECTION = "NO_SELECTION";
}
