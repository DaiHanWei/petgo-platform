package com.tailtopia.shop.order.domain;

/**
 * 订单进入 {@code COMPLETED} 的来源（Story 4.1）。
 *
 * <p>🔴 无论哪一种，<b>退货窗口都从签收时刻起算、不受影响</b>：
 * 系统替用户点了确认收货，不该顺带没收他的退货权。
 */
public enum CompletionSource {
    /** 用户主动确认收货。 */
    USER_CONFIRM,
    /** 送达起 7 日未确认，{@code @Scheduled} 自动完成（FR-102）。 */
    AUTO_TIMEOUT
}
