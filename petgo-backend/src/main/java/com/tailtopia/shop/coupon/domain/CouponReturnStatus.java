package com.tailtopia.shop.coupon.domain;

/**
 * 已取消订单用过的券的退回结果（v1.4.2 shop Story 6.2 AC3，F12）。只读推出，不落库。
 *
 * <ul>
 *   <li>{@link #RETURNED}：已退回且仍在有效期（或退回后已被别的订单用掉）；</li>
 *   <li>{@link #EXPIRED}：已退回但已过有效期（F12「kupon sudah kedaluwarsa」）；</li>
 *   <li>{@link #NOT_RETURNED}：该取消原因不退券，或退回时模板已停用作废。</li>
 * </ul>
 */
public enum CouponReturnStatus {
    RETURNED,
    EXPIRED,
    NOT_RETURNED
}
