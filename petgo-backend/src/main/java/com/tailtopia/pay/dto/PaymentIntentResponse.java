package com.tailtopia.pay.dto;

import com.tailtopia.pay.domain.PaymentIntent;

/**
 * 支付意图对外响应（Story 1.1）。<b>只暴露 {@code publicToken}，绝不暴露顺序 id / gatewayRef / 快照</b>。
 *
 * @param token    不可枚举对外订单号
 * @param purpose  用途
 * @param channel  渠道
 * @param amount   金额（最小币种单位整型）
 * @param currency 币种
 * @param status   状态
 */
public record PaymentIntentResponse(String token, String purpose, String channel, long amount,
        String currency, String status) {

    public static PaymentIntentResponse of(PaymentIntent p) {
        return new PaymentIntentResponse(
                p.getPublicToken(),
                p.getPurpose().name(),
                p.getChannel().name(),
                p.getAmount(),
                p.getCurrency(),
                p.getStatus().name());
    }
}
