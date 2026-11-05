package com.tailtopia.shop.order.dto;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;

/**
 * 订单详情「支付方式」行（v1.4.2 shop Story 6.2 AC3，D-19 / F4 · F7 · F12 · F20）：两段金额 + 各自状态。
 *
 * <p>🔴 <b>只搬运订单头快照</b>（{@code coin_amount / cash_amount / paid_at / late_paid_at / status}），不重算金额（P-1）。
 * 金额为空或 0 的一段整段为 null（NON_NULL 不下发）；两段都为空（未固化拆分的存量单）时整个对象为 null。
 * 退款后的「已退」不在这里表达，由 {@code refunds[]} 承载。
 *
 * <ul>
 *   <li>{@code coinStatus}：已付款 → {@code PAID}；待支付（含宽限期，币仍挂在单上）→ {@code HELD}（S-3 提交即扣）；
 *       未付款即取消 → {@code RETURNED}（原路退回余额）。</li>
 *   <li>{@code cashStatus}：已付款或取消后到账（{@code late_paid_at}）→ {@code PAID}；展示状态待支付 → {@code AWAITING}；
 *       其余（已取消且从未到账，含宽限期）→ {@code UNPAID}。</li>
 * </ul>
 *
 * <p>「已付款」= {@code paid_at} 非空，或状态已越过待支付且不是未付款取消（{@code paid_at} 列上线前就已付款的存量单
 * {@code paid_at} 为空，但状态已到待发货及之后——不能把它们说成「币已退回 / 未支付」）。
 */
public record PaymentMethodsView(Long coinAmount, String coinStatus, Long cashAmount, String cashStatus) {

    public static final String HELD = "HELD";
    public static final String PAID = "PAID";
    public static final String RETURNED = "RETURNED";
    public static final String AWAITING = "AWAITING";
    public static final String UNPAID = "UNPAID";

    /** @return 两段都无金额信息时为 null */
    public static PaymentMethodsView of(ShopOrder o, Instant now) {
        Long coin = o.getCoinAmount();
        Long cash = o.getCashAmount();
        if (coin == null && cash == null) {
            return null;
        }
        boolean paid = isPaid(o);
        Long coinAmount = null;
        String coinStatus = null;
        if (coin != null && coin > 0) {
            coinAmount = coin;
            coinStatus = paid ? PAID : o.getStatus() == ShopOrderStatus.PENDING_PAYMENT ? HELD : RETURNED;
        }
        Long cashAmount = null;
        String cashStatus = null;
        if (cash != null && cash > 0) {
            cashAmount = cash;
            cashStatus = paid || o.getLatePaidAt() != null ? PAID
                    : o.displayStatusAt(now) == ShopOrderStatus.PENDING_PAYMENT ? AWAITING : UNPAID;
        }
        return new PaymentMethodsView(coinAmount, coinStatus, cashAmount, cashStatus);
    }

    private static boolean isPaid(ShopOrder o) {
        if (o.getPaidAt() != null) {
            return true;
        }
        ShopOrderStatus s = o.getStatus();
        return s != ShopOrderStatus.PENDING_PAYMENT && s != ShopOrderStatus.CANCELLED;
    }
}
