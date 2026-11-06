package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.address.dto.ShippingAddressView;
import com.tailtopia.shop.order.pricing.FreeShippingProgress;
import java.time.Instant;
import java.util.List;

/**
 * 新结算试算视图（v1.4.2 shop Story 3.1 AC1.9，D-8）。
 *
 * <p>🔴 <b>不复用、不改</b>旧 {@link CheckoutPreviewView}（旧版 App 按其键集合解析，S-4）。
 *
 * <p>🔴 所有金额只来自 {@code ShopPricingCalculator}（P-1）：本视图只搬运出参，不做任何加减乘除。
 * {@code pricing.shippingDiscount} 在这里是<b>正数</b>（减了多少，计价器原值）；订单落库时再取负（3.2）。
 *
 * <p>全局 NON_NULL：null 字段不下发。
 */
public record CheckoutQuoteView(
        String source,
        ShippingAddressView address,
        String quoteToken,
        List<CourierOption> couriers,
        String selectedCourierKey,
        boolean insuranceAvailable,
        boolean insurance,
        List<QuoteLine> lines,
        PricingView pricing,
        CouponGroups coupons,
        CoinView coin,
        String strictestReturnPolicy,
        String returnPolicyCopyKey,
        boolean placeable,
        String notPlaceableReason) {

    /** 不可下单原因（判序见 {@code CheckoutQuoteService}）。 */
    public static final String NO_LINES = "NO_LINES";
    public static final String UNAVAILABLE_LINES = "UNAVAILABLE_LINES";
    public static final String NO_ADDRESS = "NO_ADDRESS";
    public static final String NOT_SERVICEABLE = "NOT_SERVICEABLE";
    public static final String QUOTE_FAILED = "QUOTE_FAILED";

    /** 手选券在候选里查不到（被占用 / 已失效 / 不是你的）。 */
    public static final String COUPON_UNAVAILABLE = "UNAVAILABLE";

    /** 一家快递报价。{@code courierKey} 即 1.1 {@code CourierRate.courierKey}。 */
    public record CourierOption(String courierKey, String courierCode, String serviceCode,
            String courierName, String serviceName, String etaText, long price,
            boolean insuranceSupported, boolean cheapest) {
    }

    /**
     * 一行商品。{@code unavailableReason}：{@code DELISTED | OUT_OF_STOCK | INSUFFICIENT_STOCK | NOT_SELLABLE}，
     * 有效为 null。{@code returnPolicy} 为生效规则（SKU 未设继承商品）。
     */
    public record QuoteLine(String skuToken, String productToken, String productName,
            String specName, long price, int qty, String mainImageUrl, String returnPolicy,
            String unavailableReason, Long availableStock) {
    }

    /**
     * 金额区（全部为计价器原值）。无报价时 {@code shippingIncluded=false}、{@code shippingFee} /
     * {@code shippingDiscount} 为 null，{@code payable / coinAmount / cashAmount} 不含运费。
     */
    public record PricingView(long goodsAmount, long discountCouponAmount, Long shippingFee,
            Long shippingDiscount, String shippingDiscountSource, long insuranceFee, long payable,
            long coinAmount, long cashAmount, long savedAmount, boolean shippingIncluded,
            FreeShippingProgress freeShippingProgress) {
    }

    public record CouponGroups(CouponGroup discount, CouponGroup freeShipping) {
    }

    /**
     * 一类券。{@code selectedToken} = 计价器<b>实际采用</b>的那张（未采用即 null —— 包括手选但
     * 减免为 0 / 输给满额免运的情形，不让用户以为券已用上）。
     */
    public record CouponGroup(String selectedToken, List<CouponOption> available,
            List<CouponOption> unavailable) {
    }

    /**
     * 一张用户券。{@code deduction} = 计价器算出的「若选它能减多少」（不可用为 0）；
     * {@code expiringSoon} = {@code validUntil − now ≤ 48h}。
     */
    public record CouponOption(String userCouponToken, String couponToken, String kind,
            String nameId, String nameEn, Long amount, Long minSpend, Instant validUntil,
            boolean expiringSoon, long deduction, String unavailableReason) {
    }

    /** PawCoin 区。{@code useCoin} 为有效值（请求 ∧ 规则开启）；{@code capReached} 取计价器 {@code coinCapped}。 */
    public record CoinView(long balance, long cap, boolean enabled, boolean useCoin,
            boolean capReached) {
    }
}
