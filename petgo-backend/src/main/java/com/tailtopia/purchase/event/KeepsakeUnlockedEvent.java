package com.tailtopia.purchase.event;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.purchase.domain.KeepsakeSku;

/**
 * 一次性解锁成功发放（GRANTED）后发布（QRIS 到账与 PawCoin 当场成交两条路径都发）。
 *
 * <p>埋点 / 订单等下游一律用 {@code @TransactionalEventListener(AFTER_COMMIT)} 订阅它，
 * <b>不得</b>订阅 {@code PaymentIntentPaidEvent}（三个新 purpose 只有 {@code KeepsakePaidHandler} 一个消费者）。
 */
public record KeepsakeUnlockedEvent(long userId, KeepsakeSku sku, long refId, long priceIdr, PayChannel channel) {
}
