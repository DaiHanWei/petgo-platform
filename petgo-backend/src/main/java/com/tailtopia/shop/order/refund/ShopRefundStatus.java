package com.tailtopia.shop.order.refund;

/**
 * 取消退款单状态（v1.4.2 shop Story 4.1 AC1.3，架构 D-15）。
 *
 * <p>币段在建单同一事务即退回，故<b>没有</b>架构草案里的 {@code COIN_REFUNDED}（无写入方，不建悬空状态，拍板点 ⑤）：
 * 现金段 &gt; 0 → {@code AWAITING_DESTINATION}；现金段 = 0 → 直接 {@code COMPLETED}。
 *
 * <pre>
 * AWAITING_DESTINATION ─TO_PAWCOIN→ COMPLETED
 *          └─TO_BANK→ PAYOUT_PENDING ─markPaidOut→ COMPLETED
 *                          └─markPayoutFailed→ PAYOUT_FAILED ─重选（TO_PAWCOIN / TO_BANK）→ …
 * </pre>
 */
public enum ShopRefundStatus {
    AWAITING_DESTINATION,
    PAYOUT_PENDING,
    PAYOUT_FAILED,
    COMPLETED;

    /** 用户还能不能选 / 改选现金去向（AC4.3）。 */
    public boolean canChooseDestination() {
        return this == AWAITING_DESTINATION || this == PAYOUT_FAILED;
    }
}
