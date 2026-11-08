package com.tailtopia.shop.order.dto;

import com.tailtopia.shared.error.AppException;
import java.util.Locale;

/**
 * 结算试算请求体（v1.4.2 shop Story 3.1 AC1.2，D-8）—— {@code POST /api/v1/me/checkout/preview}。
 *
 * <p>POST 只因参数多，<b>语义仍是只读</b>：不占券、不扣币、不锁库存、不写订单、不改购物车。
 *
 * <p>枚举类字段以字符串接收、在 {@link #sourceValue()} / {@link #couponModeValue()} 里解析：
 * Jackson 直接反序列化非法枚举值会变 400，而契约要求 422 validation。
 *
 * @param source                  {@code CART | BUY_NOW}，必填
 * @param buyNow                  {@code source=BUY_NOW} 时必填
 * @param addressToken            可空 = 用默认地址
 * @param courierKey              {@code <courierCode>:<courierServiceCode>}（1.1 {@code CourierRate.keyOf}）；可空 = 最便宜
 * @param insurance               缺省 false
 * @param couponMode              {@code AUTO | MANUAL}，缺省 AUTO
 * @param discountCouponToken     MANUAL 时生效（用户券 public_token）；null = 不用满减券
 * @param freeShippingCouponToken MANUAL 时生效（用户券 public_token）；null = 不用免运券
 * @param useCoin                 缺省 true（PRD「有余额默认勾选」）
 */
public record CheckoutPreviewRequest(
        String source,
        BuyNow buyNow,
        String addressToken,
        String courierKey,
        Boolean insurance,
        String couponMode,
        String discountCouponToken,
        String freeShippingCouponToken,
        Boolean useCoin) {

    /** BUY_NOW 数量上限（与购物车一致的单次购买上限）。 */
    public static final int MAX_QTY = 99;

    /** 立即购买的一行。{@code entrySource} 只透传给 3.2 下单归因，试算不读。 */
    public record BuyNow(String skuToken, Integer qty, String entrySource) {
    }

    public enum Source {
        CART,
        BUY_NOW
    }

    public enum CouponMode {
        AUTO,
        MANUAL
    }

    /** 缺省或非法 → 422。 */
    public Source sourceValue() {
        if (source == null || source.isBlank()) {
            throw AppException.validation("source 必填");
        }
        try {
            return Source.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AppException.validation("source 非法");
        }
    }

    /** 缺省 AUTO；非法 → 422。 */
    public CouponMode couponModeValue() {
        if (couponMode == null || couponMode.isBlank()) {
            return CouponMode.AUTO;
        }
        try {
            return CouponMode.valueOf(couponMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AppException.validation("couponMode 非法");
        }
    }

    public boolean insuranceValue() {
        return Boolean.TRUE.equals(insurance);
    }

    public boolean useCoinValue() {
        return useCoin == null || useCoin;
    }

    /** BUY_NOW 行校验：缺 buyNow / skuToken → 422；qty 缺省 1，越界 → 422。 */
    public BuyNow requireBuyNow() {
        if (buyNow == null || buyNow.skuToken() == null || buyNow.skuToken().isBlank()) {
            throw AppException.validation("立即购买需指定规格");
        }
        int qty = buyNow.qty() == null ? 1 : buyNow.qty();
        if (qty < 1 || qty > MAX_QTY) {
            throw AppException.validation("数量须在 1 到 " + MAX_QTY + " 之间");
        }
        return new BuyNow(buyNow.skuToken().trim(), qty, buyNow.entrySource());
    }

    public String addressTokenOrNull() {
        return addressToken == null || addressToken.isBlank() ? null : addressToken.trim();
    }
}
