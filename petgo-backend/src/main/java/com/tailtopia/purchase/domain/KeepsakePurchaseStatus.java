package com.tailtopia.purchase.domain;

/**
 * 购买行状态（{@code keepsake_purchases.status}）。
 *
 * <p>{@link #DUPLICATE_PAID}：到账时业务行已解锁（钱多收了，交人工退款）；{@link #ORPHAN_PAID}：到账时业务行不存在或发放失败
 * （钱到了没发出去，交人工）。两者都是「已付款」，意图仍 PAID —— 到账状态绝不因发放问题回滚。
 */
public enum KeepsakePurchaseStatus {
    PENDING,
    PAID,
    CANCELED,
    EXPIRED,
    DUPLICATE_PAID,
    ORPHAN_PAID;

    /** 钱已到账（含两类异常到账）。 */
    public boolean isPaid() {
        return this == PAID || this == DUPLICATE_PAID || this == ORPHAN_PAID;
    }
}
