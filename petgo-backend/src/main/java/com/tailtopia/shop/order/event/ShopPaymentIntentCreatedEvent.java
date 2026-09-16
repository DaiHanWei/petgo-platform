package com.tailtopia.shop.order.event;

/**
 * 电商支付意图「首次创建」事件（Story 1-2 · AD-S9(b)）：支付漏斗的入口那一格。
 *
 * <p>🔴 <b>只在真的换了新意图时发</b>：{@code ShopOrderPaymentService.ensureIntent} 是幂等的
 * （重复点「去支付」取回同一个意图、同一个二维码），照「调用了就发」写法，用户连点三次
 * 漏斗入口就凭空多两个，转化率被自己的重试稀释掉。判据是「本次意图 token 与订单上已挂的
 * {@code payment_intent_token} 不同」。
 *
 * <p>🔴 <b>不带订单号</b>：{@code orderToken} 对运营有用但对漏斗没用，且它是对外标识，
 * 进埋点等于把标识面扩到第三方（SHOP-NFR-01）。
 *
 * @param userId     下单用户（监听方转成 {@code AnalyticsDistinctId}，不直接上报）
 * @param payChannel {@code PayChannel} 名
 * @param amount     订单总额（最小币种单位整型）
 * @param hasPawcoin 是否含 PawCoin 抵扣段
 */
public record ShopPaymentIntentCreatedEvent(long userId, String payChannel, long amount,
        boolean hasPawcoin) {
}
