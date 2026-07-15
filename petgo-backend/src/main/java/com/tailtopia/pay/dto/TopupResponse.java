package com.tailtopia.pay.dto;

/**
 * 充值下单响应（Story 1.3）。<b>只回对外 {@code intentToken}（非自增 id）</b> + 付款载荷
 * （QRIS 二维码串）；前端据 payload 渲染付款，回调到账后余额自动增加。
 *
 * @param intentToken 支付意图对外 token（轮询/查询用）
 * @param channel     支付渠道
 * @param amount      充值金额（IDR 最小单位整型）
 * @param coins       到账 koin（1:1）
 * @param payload     付款载荷（二维码串 / deeplink；stub 为伪串）
 */
public record TopupResponse(String intentToken, String channel, long amount, long coins, String payload) {
}
