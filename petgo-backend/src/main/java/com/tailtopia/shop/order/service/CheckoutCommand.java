package com.tailtopia.shop.order.service;

import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;

/**
 * 结算装配链路的入参（v1.4.2 shop Story 3.1）：请求体 + 两个只供内部「旧结算模式」用的开关。
 *
 * @param defaultAddressFallback {@code addressToken} 为空时是否回落默认地址（新端点 true；
 *                               旧 {@code GET /me/checkout} false —— 旧版 App 不传地址即「无地址预览」）
 * @param couponsEnabled         是否读取用户券（旧结算模式 false：旧版 App 不认识券）
 * @param skipInvalidCartLines   CART 来源时是否<b>跳过</b>库内仍勾着的失效行（V1.4.2 验收拍板 DEC-142-01）。
 *                               新端点 true：新版 App 把失效行显示为未勾选、不计入合计（8-3 AC4.3 / 3-1 §50），
 *                               不能再被一件「看起来没勾」的商品拦住整单；旧结算模式 false：旧版 App 如实显示
 *                               勾选状态，仍按「勾着的失效行逐行报不可买」处理（S-4）。
 */
public record CheckoutCommand(CheckoutPreviewRequest request, boolean defaultAddressFallback,
        boolean couponsEnabled, boolean skipInvalidCartLines) {

    /** 兼容旧三参构造：不跳过失效行（旧口径）。 */
    public CheckoutCommand(CheckoutPreviewRequest request, boolean defaultAddressFallback,
            boolean couponsEnabled) {
        this(request, defaultAddressFallback, couponsEnabled, false);
    }

    /** 新端点 {@code POST /me/checkout/preview}。 */
    public static CheckoutCommand of(CheckoutPreviewRequest request) {
        return new CheckoutCommand(request, true, true, true);
    }
}
