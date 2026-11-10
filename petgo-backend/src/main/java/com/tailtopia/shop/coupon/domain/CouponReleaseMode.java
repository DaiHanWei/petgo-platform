package com.tailtopia.shop.coupon.domain;

/**
 * 订单取消时用户券的处置（Story 2.1 拍板点 ③，D-3）。
 *
 * <p>刻意不引用 4.1 的 {@code CancelReason}（消除 2.1 → 4.1 前向依赖）：4.1 以
 * {@code reason.refundCoupon() ? RETURN : KEEP} 调 {@code CouponLedgerService.releaseOnCancel}。
 */
public enum CouponReleaseMode {
    /** 退回：LOCKED / USED → AVAILABLE（模板已停用且选了作废 → VOIDED），清订单字段。 */
    RETURN,
    /** 不动（部分取消、用户原因被退回，FR-130.7）。 */
    KEEP
}
