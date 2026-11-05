package com.tailtopia.shop.order.service;

import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.pricing.PricingResult;
import com.tailtopia.shop.order.service.CheckoutQuoteService.CheckoutComputation;
import com.tailtopia.shop.order.service.CheckoutQuoteService.QuoteOutcome;
import com.tailtopia.shop.order.service.CheckoutService.CheckoutPreview;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 旧结算模式（v1.4.2 shop Story 3.1 AC4，S-4）：旧版 App 的 {@code GET /me/checkout} 走与新端点<b>同一条</b>
 * 装配链路，只是入参固定为「购物车 · 最便宜快递 · 不用券 · 用币 · 不保价」。
 *
 * <p>本 story 只落「预览」一半（{@link #previewCommand} + {@link #toLegacyPreview}）；3.2 补下单那一半。
 *
 * <p>🔴 不做金额计算（P-1）：唯一的变换是 {@code shippingDiscount} 取负 —— 旧口径「免运抵扣 ≤ 0」，
 * 计价器给的是正数减免额。
 */
@Component
public class LegacyCheckoutAdapter {

    /**
     * 旧结算预览入参：{@code source=CART}、最便宜快递、不用券、{@code useCoin=true}、不保价。
     * {@code addressToken} 为空即「无地址预览」（不回落默认地址：旧版 App 不传地址时 {@code address} 一直是 null）。
     */
    public CheckoutCommand previewCommand(String addressToken) {
        String token = addressToken == null || addressToken.isBlank() ? null : addressToken;
        return new CheckoutCommand(new CheckoutPreviewRequest(
                CheckoutPreviewRequest.Source.CART.name(), null, token, null, false,
                CheckoutPreviewRequest.CouponMode.MANUAL.name(), null, null, true),
                false, false);
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
