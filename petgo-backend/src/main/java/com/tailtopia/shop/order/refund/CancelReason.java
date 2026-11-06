package com.tailtopia.shop.order.refund;

import com.tailtopia.shop.coupon.domain.CouponReleaseMode;

/**
 * 订单取消原因（v1.4.2 shop Story 3.2 AC2，架构 D-14；落 {@code shop_orders.cancel_reason}）。
 *
 * <p>四个属性<b>由类型推出</b>（照 {@code ReturnType} 构造器写法），不给运营手工开关：
 * <ul>
 *   <li>{@code platformFault}：是否平台原因；</li>
 *   <li>{@code refundCoupon}：取消时券是否退回（{@link #couponReleaseMode()}）；</li>
 *   <li>{@code refundShipping}：整单退时是否连运费与保价费一起退。{@code LOST_DAMAGED} 按「商品实付」赔、
 *       不退运费保价（PRD FR-172「丢件破损赔付 = 应付 − 实付运费 − 保价费」）；未付款两类的 true 只表示
 *       「整笔原路退回」，不产生退款单；</li>
 *   <li>{@code compensate}：是否另给补偿（4.1）。</li>
 * </ul>
 *
 * <p>{@code LATE_PAYMENT.refundCoupon=false}：原单取消时券已按 {@code PAYMENT_TIMEOUT} 退过。
 */
public enum CancelReason {

    USER_UNPAID_CANCEL(false, true, true, false),
    PAYMENT_TIMEOUT(false, true, true, false),
    OUT_OF_STOCK(true, true, true, true),
    WAYBILL_FAILED(true, true, true, true),
    PICKUP_FAILED(true, true, true, true),
    COURIER_FAULT(true, true, true, true),
    USER_FAULT_RETURNED(false, false, false, false),
    LOST_DAMAGED(true, true, false, true),
    LATE_PAYMENT(false, false, false, false);

    private final boolean platformFault;
    private final boolean refundCoupon;
    private final boolean refundShipping;
    private final boolean compensate;

    CancelReason(boolean platformFault, boolean refundCoupon, boolean refundShipping,
            boolean compensate) {
        this.platformFault = platformFault;
        this.refundCoupon = refundCoupon;
        this.refundShipping = refundShipping;
        this.compensate = compensate;
    }

    public boolean platformFault() {
        return platformFault;
    }

    public boolean refundCoupon() {
        return refundCoupon;
    }

    public boolean refundShipping() {
        return refundShipping;
    }

    public boolean compensate() {
        return compensate;
    }

    /**
     * 退券方式：本线所有 {@code CouponLedgerService.releaseOnCancel} 调用点统一取它，不在各处重复三元表达式。
     */
    public CouponReleaseMode couponReleaseMode() {
        return refundCoupon ? CouponReleaseMode.RETURN : CouponReleaseMode.KEEP;
    }
}
