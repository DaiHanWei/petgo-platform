package com.tailtopia.shop.order.pricing;

import java.util.List;

/**
 * 计价器输出（Story 1.2，D-7）。🔴 不含任何行级单价 / 行级分摊额（NFR7）。
 *
 * <p>不变式：{@code payable = goodsNetAmount + shippingFee − shippingDiscount + insuranceFee}，
 * {@code coinAmount + cashAmount = payable}。
 *
 * @param goodsAmount                       商品金额 Σ 单价 × 数量
 * @param discountCouponAmount              满减券抵扣
 * @param goodsNetAmount                    商品实付（保价与免运门槛的基数）
 * @param shippingFee                       所选运费（运费未知为 0）
 * @param shippingDiscount                  运费减免
 * @param shippingDiscountSource            减免来源
 * @param insuranceFee                      保价费
 * @param payable                           应付
 * @param coinAmount                        PawCoin 支付额
 * @param cashAmount                        现金支付额
 * @param coinCapped                        用币被单笔上限截断（D13 提醒）
 * @param savedAmount                       已省 = 满减 + 运费减免（不含 PawCoin，FR-131）
 * @param freeShippingProgress              满额免运进度；未配置门槛为 null
 * @param appliedDiscountUserCouponToken    实际使用的满减券；无为 null
 * @param appliedFreeShippingUserCouponToken 实际使用的免运券；无为 null
 * @param manualSelectionInvalid            手选券无效（3.2 据此回 409 coupon-invalid）
 * @param shippingKnown                     运费是否已知
 * @param evaluations                       每张入参券的评估，顺序同入参
 */
public record PricingResult(long goodsAmount, long discountCouponAmount, long goodsNetAmount,
        long shippingFee, long shippingDiscount, ShippingDiscountSource shippingDiscountSource,
        long insuranceFee, long payable, long coinAmount, long cashAmount, boolean coinCapped,
        long savedAmount, FreeShippingProgress freeShippingProgress,
        String appliedDiscountUserCouponToken, String appliedFreeShippingUserCouponToken,
        boolean manualSelectionInvalid, boolean shippingKnown, List<CouponEvaluation> evaluations) {
}
