package com.tailtopia.pay.dto;

import com.tailtopia.pay.domain.PaymentIntent;

/**
 * 支付意图对外响应（Story 1.1）。<b>只暴露 {@code publicToken}，绝不暴露顺序 id / gatewayRef / 快照</b>
 * （{@code displayNo} 为拼装展示号，沿 299 先例，查询键仍是 token）。
 *
 * @param token     不可枚举对外订单号
 * @param purpose   用途
 * @param channel   渠道
 * @param amount    金额（最小币种单位整型）
 * @param currency  币种
 * @param status    状态
 * @param displayNo 可读支付号（bug 326，PAY 前缀-日期-序号，仅展示；见 {@link PaymentDisplayNo}）
 */
public record PaymentIntentResponse(String token, String purpose, String channel, long amount,
        String currency, String status, String displayNo) {

    public static PaymentIntentResponse of(PaymentIntent p) {
        return new PaymentIntentResponse(
                p.getPublicToken(),
                p.getPurpose().name(),
                p.getChannel().name(),
                p.getAmount(),
                p.getCurrency(),
                p.getStatus().name(),
                PaymentDisplayNo.of(p));
    }
}
