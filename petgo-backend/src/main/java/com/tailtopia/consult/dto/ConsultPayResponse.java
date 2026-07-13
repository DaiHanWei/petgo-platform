package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import java.time.Instant;

/**
 * 限时支付响应（Story 3.4）。两形态（Jackson NON_NULL 省略空侧，照 2-3 {@code UnlockResponse}）：
 * <ul>
 *   <li>{@code mode=DONE} + {@code order}：PawCoin 站内即时支付成功——已建订单 + IM 会话，前端跳会话页。</li>
 *   <li>{@code mode=PAYMENT_REQUIRED} + {@code payment}：QRIS 现金——回支付信息，前端引导支付，
 *       到账后由 {@code ConsultPaidHandler} 建单建会话，用户再进会话。</li>
 * </ul>
 *
 * @param mode    DONE | PAYMENT_REQUIRED
 * @param order   同步支付成功的订单视图（现金态为 null）
 * @param payment 现金待支付信息（同步态为 null）
 */
public record ConsultPayResponse(String mode, OrderView order, PaymentIntentResponse payment) {

    /** 订单精简视图（不外泄内部 id / 快照金额明细，仅前端跳转所需）。 */
    public record OrderView(String orderToken, String status, Instant paidAt, Instant sessionStartedAt) {
        public static OrderView of(ConsultOrder o) {
            return new OrderView(o.getOrderToken(), o.getStatus().name(), o.getPaidAt(),
                    o.getSessionStartedAt());
        }
    }

    public static ConsultPayResponse done(ConsultOrder order) {
        return new ConsultPayResponse("DONE", OrderView.of(order), null);
    }

    public static ConsultPayResponse paymentRequired(PaymentIntentResponse payment) {
        return new ConsultPayResponse("PAYMENT_REQUIRED", null, payment);
    }
}
