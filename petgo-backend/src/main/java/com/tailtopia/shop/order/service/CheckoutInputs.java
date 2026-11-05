package com.tailtopia.shop.order.service;

import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.coupon.service.CouponQueryService.OwnedCoupon;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.pricing.FreeShippingRule;
import java.util.List;
import java.util.Map;

/**
 * 只读事务里一次取齐的结算入参（v1.4.2 shop Story 3.1；3.2 复用）。
 *
 * @param cart           {@code source=CART} 时的购物车视图；BUY_NOW 为 null
 * @param lines          参与本单的行（CART = 选中有效行；BUY_NOW = 那一行）
 * @param address        收货地址；无为 null
 * @param candidates     本人可用券（AVAILABLE 且在有效期内）；旧结算模式为空
 * @param manualExtras   MANUAL 手选、但不在候选里的券（按 token 查到的本人券；查不到的不在此）
 * @param rule           满额免运规则
 * @param insuranceRateBp 保价费率
 * @param coinEnabled    电商 PawCoin 总开关
 * @param allowShippingDeduction 运费可否用币抵扣
 * @param coinCap        单笔上限
 * @param coinBalance    余额
 */
public record CheckoutInputs(
        CheckoutPreviewRequest.Source source,
        CartView cart,
        List<ResolvedCheckoutLine> lines,
        ShippingAddress address,
        List<OwnedCoupon> candidates,
        Map<String, OwnedCoupon> manualExtras,
        FreeShippingRule rule,
        int insuranceRateBp,
        boolean coinEnabled,
        boolean allowShippingDeduction,
        long coinCap,
        long coinBalance) {

    public List<ResolvedCheckoutLine> pricedLines() {
        return lines.stream().filter(ResolvedCheckoutLine::priced).toList();
    }
}
