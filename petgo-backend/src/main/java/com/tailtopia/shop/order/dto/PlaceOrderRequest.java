package com.tailtopia.shop.order.dto;

/**
 * 下单请求体（{@code POST /api/v1/me/shop-orders}）。
 *
 * <p>v1.4.2 shop Story 3.2 AC3.1：在原三字段 record 上<b>追加</b>新结算模式字段（原为
 * {@code MeCheckoutController.PlaceOrderRequest}，移到 dto 包供 {@code LegacyCheckoutAdapter} 复用；JSON 形状不变）。
 *
 * <p>🔴 <b>判别只看 {@code quoteToken}</b>（架构 D-8）：为空或空白 → 旧结算模式（旧版 App，S-4，其余新字段一律忽略）；
 * 非空 → 新结算模式。
 *
 * @param addressToken            收货地址（必填）
 * @param entrySource             归因兜底：用户从哪个入口进的商品（购物车行自带来源时以行为准）
 * @param triggerType             归因兜底：触发类型
 * @param source                  {@code CART | BUY_NOW}（新模式必填）
 * @param buyNow                  {@code source=BUY_NOW} 时必填；{@code entrySource} 为该行归因
 * @param quoteToken              3.1 试算返回的报价 token（锁定快递与运费，I7）
 * @param courierKey              所选快递 {@code <courierCode>:<courierServiceCode>}，必须在该报价中
 * @param insurance               是否保价（快递不支持时忽略）
 * @param discountCouponToken     所选满减券（用户券 token）；null = 不用
 * @param freeShippingCouponToken 所选免运券（用户券 token）；null = 不用
 * @param useCoin                 是否使用 PawCoin，缺省 true
 * @param agreeReturnPolicy       必须为 true（新模式），否则 422
 */
public record PlaceOrderRequest(
        String addressToken,
        String entrySource,
        String triggerType,
        String source,
        CheckoutPreviewRequest.BuyNow buyNow,
        String quoteToken,
        String courierKey,
        Boolean insurance,
        String discountCouponToken,
        String freeShippingCouponToken,
        Boolean useCoin,
        Boolean agreeReturnPolicy) {

    /** 旧版 App 的三字段请求（及内部 / 集成测试调用）。 */
    public static PlaceOrderRequest legacy(String addressToken, String entrySource,
            String triggerType) {
        return new PlaceOrderRequest(addressToken, entrySource, triggerType, null, null, null,
                null, null, null, null, null, null);
    }

    /** 新结算模式 ⇔ 带 {@code quoteToken}。 */
    public boolean newCheckoutMode() {
        return quoteToken != null && !quoteToken.isBlank();
    }
}
