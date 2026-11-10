package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;

/**
 * 「我已付款」查单结果（v1.4.2 shop Story 3.3 AC9.3）。
 *
 * @param paid          {@code status ∉ {PENDING_PAYMENT, CANCELLED}}；App 据 {@code false} 显示 E4「还没收到付款，已付款的话稍等再查」
 * @param status        订单真实状态
 * @param displayStatus 对用户展示的状态（宽限期待支付显示 CANCELLED，S-7）
 */
public record PaymentCheckView(boolean paid, String status, String displayStatus) {

    public static PaymentCheckView of(ShopOrder order, Instant now) {
        ShopOrderStatus st = order.getStatus();
        boolean paid = st != ShopOrderStatus.PENDING_PAYMENT && st != ShopOrderStatus.CANCELLED;
        return new PaymentCheckView(paid, st.name(), order.displayStatusAt(now).name());
    }
}
