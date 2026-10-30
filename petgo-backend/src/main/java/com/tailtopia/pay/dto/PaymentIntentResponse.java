package com.tailtopia.pay.dto;

import com.tailtopia.pay.domain.PaymentIntent;
import java.time.Instant;

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
 * @param expiresAt 付款窗过期时刻（UTC，服务端权威；V1.4.1 QRIS 支付页倒计时 / 「Batas bayar」用）。
 *                  null = 该意图无时间过期（如 PawCoin 扣币即时成交）；旧版客户端忽略此字段
 */
public record PaymentIntentResponse(String token, String purpose, String channel, long amount,
        String currency, String status, String displayNo, Instant expiresAt) {

    /** 兼容构造（不带过期时刻）。 */
    public PaymentIntentResponse(String token, String purpose, String channel, long amount,
            String currency, String status, String displayNo) {
        this(token, purpose, channel, amount, currency, status, displayNo, null);
    }

    public static PaymentIntentResponse of(PaymentIntent p) {
        return new PaymentIntentResponse(
                p.getPublicToken(),
                p.getPurpose().name(),
                p.getChannel().name(),
                p.getAmount(),
                p.getCurrency(),
                p.getStatus().name(),
                PaymentDisplayNo.of(p),
                p.getExpiresAt());
    }
}
