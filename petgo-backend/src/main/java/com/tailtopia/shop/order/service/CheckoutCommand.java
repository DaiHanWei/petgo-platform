package com.tailtopia.shop.order.service;

import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;

/**
 * 结算装配链路的入参（v1.4.2 shop Story 3.1）：请求体 + 两个只供内部「旧结算模式」用的开关。
 *
 * @param defaultAddressFallback {@code addressToken} 为空时是否回落默认地址（新端点 true；
 *                               旧 {@code GET /me/checkout} false —— 旧版 App 不传地址即「无地址预览」）
 * @param couponsEnabled         是否读取用户券（旧结算模式 false：旧版 App 不认识券）
 */
public record CheckoutCommand(CheckoutPreviewRequest request, boolean defaultAddressFallback,
        boolean couponsEnabled) {

    /** 新端点 {@code POST /me/checkout/preview}。 */
    public static CheckoutCommand of(CheckoutPreviewRequest request) {
        return new CheckoutCommand(request, true, true);
    }
}
