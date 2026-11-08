package com.tailtopia.shop.order.service;

import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.PlaceOrderRequest;

/**
 * 提交订单的统一入参（v1.4.2 shop Story 3.2 AC3 / AC5.2）：新结算模式由请求体直接构造，旧结算模式由
 * {@link LegacyCheckoutAdapter#placeCommand} 装配（询价已在事务外完成）—— 两者交给<b>同一个</b>
 * {@link CheckoutService#place} 提交事务（报价绑定、计价、券、币一视同仁）。
 *
 * @param checkoutSource       落 {@code shop_orders.checkout_source}（{@code CART | BUY_NOW | LEGACY}）
 * @param checkout             结算装配入参（行来源、地址、快递、保价、券、用币；与 3.1 试算共用 {@link CheckoutLineResolver}）
 * @param quoteToken           锁定的报价 token；旧结算模式询价未取得时为 null（提交时 409 {@code quote-expired}）
 * @param agreeReturnPolicy    同意退货规则（旧结算模式视为已同意）
 * @param fallbackEntrySource  归因兜底（CART：请求体 {@code entrySource}；BUY_NOW：{@code buyNow.entrySource}）
 * @param fallbackTriggerType  归因兜底
 */
public record PlaceCommand(CheckoutSource checkoutSource, CheckoutCommand checkout,
        String quoteToken, boolean agreeReturnPolicy, String fallbackEntrySource,
        String fallbackTriggerType) {

    /**
     * 新结算模式（请求体带 {@code quoteToken}）。券一律按 MANUAL 交计价器（只用用户在试算页确认过的那两张），
     * {@code source} 缺省或非法 → 422 {@code validation}；BUY_NOW 的 {@code qty} 越界 → 422（解析时校验）。
     */
    public static PlaceCommand of(PlaceOrderRequest req) {
        CheckoutPreviewRequest preview = new CheckoutPreviewRequest(req.source(), req.buyNow(),
                req.addressToken(), req.courierKey(), req.insurance(),
                CheckoutPreviewRequest.CouponMode.MANUAL.name(), req.discountCouponToken(),
                req.freeShippingCouponToken(), req.useCoin());
        CheckoutPreviewRequest.Source source = preview.sourceValue();
        String entry = req.entrySource();
        if (source == CheckoutPreviewRequest.Source.BUY_NOW && req.buyNow() != null
                && req.buyNow().entrySource() != null) {
            entry = req.buyNow().entrySource();
        }
        // 地址不回落默认地址：提交必须明确带 addressToken（空 → 422 not-placeable）
        return new PlaceCommand(
                source == CheckoutPreviewRequest.Source.BUY_NOW ? CheckoutSource.BUY_NOW
                        : CheckoutSource.CART,
                new CheckoutCommand(preview, false, true, true), req.quoteToken().trim(),
                Boolean.TRUE.equals(req.agreeReturnPolicy()), entry, req.triggerType());
    }
}
