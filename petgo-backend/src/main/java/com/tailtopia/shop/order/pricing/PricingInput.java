package com.tailtopia.shop.order.pricing;

import java.time.Instant;
import java.util.List;

/**
 * 计价器总入参（Story 1.2，D-7）。🔴 {@code now} 由调用方给，计价器不读时钟。
 *
 * @param lines               商品行
 * @param coupons             可参与本单的用户券（含已过期需展示者）
 * @param selection           选券方式；null 视同 AUTO
 * @param rule                满额免运规则；null 视同不做满额免运
 * @param shipping            运费；null = 运费未知
 * @param insuranceApplicable 用户勾选保价 且 所选快递支持保价（调用方判定）
 * @param insuranceRateBp     保价费率（基点），{@code shipping_settings.insurance_rate_bp}，默认 50
 * @param coin                PawCoin 选项
 * @param now                 计价时刻（只用于券有效期判断）
 */
public record PricingInput(List<PricingLine> lines, List<PricingCoupon> coupons,
        CouponSelection selection, FreeShippingRule rule, ShippingChoice shipping,
        boolean insuranceApplicable, int insuranceRateBp, CoinOptions coin, Instant now) {
}
