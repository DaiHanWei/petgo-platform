package com.tailtopia.shop.order.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.PlaceOrderRequest;
import com.tailtopia.shop.order.pricing.PricingResult;
import com.tailtopia.shop.order.service.CheckoutQuoteService.CheckoutComputation;
import com.tailtopia.shop.order.service.CheckoutQuoteService.QuoteOutcome;
import com.tailtopia.shop.order.service.CheckoutService.CheckoutPreview;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 旧结算模式（v1.4.2 shop Story 3.1 AC4 / 3.2 AC5，S-4）：旧版 App（≤1.4.1）的 {@code GET /me/checkout} 与
 * {@code POST /me/shop-orders}（不带 {@code quoteToken}）走与新端点<b>同一条</b>装配链路 / 提交事务，只是入参固定为
 * 「购物车 · 最便宜快递 · 不用券 · 用币 · 不保价 · 视为已同意退货规则」（一组默认值，只在 {@link #legacyRequest} 一处定义）。
 *
 * <p>🔴 不做金额计算（P-1）：唯一的变换是 {@code shippingDiscount} 取负 —— 旧口径「免运抵扣 ≤ 0」，
 * 计价器给的是正数减免额。
 *
 * <p>🔴 下单时的询价在 {@link #placeCommand} 里完成：<b>非事务</b>，在提交事务之前（P-7）。
 */
@Component
public class LegacyCheckoutAdapter {

    /** 旧结算模式：不保价。 */
    static final boolean LEGACY_INSURANCE = false;
    /** 旧结算模式：自动用币（受 {@code rules.enabled} 约束）。 */
    static final boolean LEGACY_USE_COIN = true;
    /** 旧结算模式：不读用户券（旧版 App 不认识券）。 */
    static final boolean LEGACY_COUPONS_ENABLED = false;
    /** 旧结算模式：旧版 App 客户端已有勾选，视为已同意退货规则。 */
    static final boolean LEGACY_AGREE_RETURN_POLICY = true;

    private final CheckoutQuoteService quotesChain;

    public LegacyCheckoutAdapter(CheckoutQuoteService quotesChain) {
        this.quotesChain = quotesChain;
    }

    /**
     * 旧结算预览入参：{@code source=CART}、最便宜快递、不用券、{@code useCoin=true}、不保价。
     * {@code addressToken} 为空即「无地址预览」（不回落默认地址：旧版 App 不传地址时 {@code address} 一直是 null）。
     */
    public CheckoutCommand previewCommand(String addressToken) {
        String token = addressToken == null || addressToken.isBlank() ? null : addressToken;
        return legacyCommand(token, null);
    }

    /**
     * 旧版 App 下单 → 与新模式同一个 {@link PlaceCommand}（v1.4.2 shop Story 3.2 AC5.2）。
     *
     * <p>先按地址<b>实时询价</b>（1.1，30 分钟复用照常生效；本方法不加事务）：失败 → 503 {@code quote-failed}；
     * 0 家 / 区域未开通 → 422 {@code not-placeable}；取最便宜一家（1.1 已按价格升序、同价按快递名 / 服务码）。
     * 有不可买行或一件没选时<b>不报询价错误</b>、不带 {@code quoteToken}：交给提交事务给出旧版 App 认识的
     * 「购物车为空 / 请至少选择一件商品 / 409 逐行明细」。
     *
     * @throws AppException {@code addressToken} 为空 → 422 {@code validation}「请选择收货地址」（沿用旧文案与码）
     */
    public PlaceCommand placeCommand(long userId, PlaceOrderRequest req) {
        if (req == null || req.addressToken() == null || req.addressToken().isBlank()) {
            throw AppException.validation("请选择收货地址");
        }
        String address = req.addressToken().trim();
        CheckoutComputation c = quotesChain.compute(userId, previewCommand(address));

        String quoteToken = null;
        String courierKey = null;
        boolean linesPlaceable = !c.inputs().lines().isEmpty()
                && c.inputs().lines().stream().allMatch(l -> l.unavailableReason() == null);
        if (linesPlaceable) {
            switch (c.quote().status()) {
                case OK -> {
                    quoteToken = c.quote().result().quoteToken();
                    courierKey = c.selectedCourier().courierKey();
                }
                case QUOTE_FAILED -> throw new QuoteFailedException("运费询价失败，请稍后重试");
                case NOT_SERVICEABLE -> throw AppException.notPlaceable(
                        CheckoutService.notServiceableDetail(c.inputs().address()));
                case NO_ADDRESS, SKIPPED -> {
                    // 不可达（有地址且有可计价行）；不带 token，提交时 409 quote-expired
                }
            }
        }
        return new PlaceCommand(CheckoutSource.LEGACY, legacyCommand(address, courierKey),
                quoteToken, LEGACY_AGREE_RETURN_POLICY, req.entrySource(), req.triggerType());
    }

    /** 旧结算模式默认值的唯一定义处（预览与下单共用）。 */
    private static CheckoutCommand legacyCommand(String addressToken, String courierKey) {
        return new CheckoutCommand(legacyRequest(addressToken, courierKey), false,
                LEGACY_COUPONS_ENABLED);
    }

    private static CheckoutPreviewRequest legacyRequest(String addressToken, String courierKey) {
        return new CheckoutPreviewRequest(CheckoutPreviewRequest.Source.CART.name(), null,
                addressToken, courierKey, LEGACY_INSURANCE,
                CheckoutPreviewRequest.CouponMode.MANUAL.name(), null, null, LEGACY_USE_COIN);
    }

    /**
     * 新链路结果 → 旧 {@link CheckoutPreview}（字段集合不变）。
     *
     * <ul>
     *   <li>有报价：{@code shippingFee} = 最便宜快递运费；{@code shippingDiscount} = −计价器减免；
     *       {@code payableTotal / coinAmount / cashAmount} = 计价器值；</li>
     *   <li>{@code serviceable=false} 当且仅当 {@code NOT_SERVICEABLE}（金额全 null，与旧「超范围」同形）；</li>
     *   <li>无地址 / 询价失败 / 一件没选：金额全 null、{@code serviceable=true}（旧「quote == null」分支同形；
     *       {@code shipping_zones.fee} 停读，D-9）。</li>
     * </ul>
     */
    public CheckoutPreview toLegacyPreview(CheckoutComputation c) {
        CheckoutInputs in = c.inputs();
        Map<String, ReturnPolicy> policies = new LinkedHashMap<>();
        for (ResolvedCheckoutLine l : in.lines()) {
            policies.put(l.skuToken(), l.returnPolicy());
        }
        boolean serviceable = c.quote().status() != QuoteOutcome.Status.NOT_SERVICEABLE;
        PricingResult pr = c.pricing();
        boolean priced = c.quote().ok() && pr.shippingKnown();
        return new CheckoutPreview(in.cart(), in.address(),
                priced ? pr.shippingFee() : null,
                priced ? Math.negateExact(pr.shippingDiscount()) : null,
                priced ? pr.payable() : null,
                priced ? pr.coinAmount() : null,
                priced ? pr.cashAmount() : null,
                in.coinBalance(), in.coinCap(),
                priced && pr.coinCapped(),
                serviceable, policies);
    }
}
