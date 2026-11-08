package com.tailtopia.shop.logistics;

import com.tailtopia.shop.order.pricing.FreeShippingProgress;

/**
 * 商品详情「运费 Rp X 起」估算（v1.4.2 shop Story 3.1 AC2，D-9 / FR-127）。NON_NULL：null 不下发。
 *
 * @param serviceable          能否送达；无收货地 / 询价失败为 null
 * @param lowestFee            本单所有快递最低运费；不可配送 / 未询价为 null
 * @param quoteFailed          Biteship 失败（HTTP 仍 200，App 显示「运费暂时无法获取 · 重试」）
 * @param freeShippingProgress 计价器给（基数 = 商品实付）；门槛 0 或无收货地为 null
 * @param reason               {@code null | NO_DESTINATION | NOT_SERVICEABLE | QUOTE_FAILED}
 */
public record ShippingEstimateView(Boolean serviceable, Long lowestFee, boolean quoteFailed,
        FreeShippingProgress freeShippingProgress, String reason) {

    public static final String NO_DESTINATION = "NO_DESTINATION";
    public static final String NOT_SERVICEABLE = "NOT_SERVICEABLE";
    public static final String QUOTE_FAILED = "QUOTE_FAILED";

    public static ShippingEstimateView noDestination() {
        return new ShippingEstimateView(null, null, false, null, NO_DESTINATION);
    }
}
