package com.tailtopia.profile.slot.domain;

/** 名额购买类型（V1.4.0 multi-pet Story 2.2 · AD-5，落库 varchar + CHECK）。 */
public enum PetSlotPurchaseKind {
    /** 新租：到账 / 扣币后新建 N 行名额。 */
    RENT,
    /** 续租：所选生效中名额 +30 天。 */
    RENEW,
    /** 运营补发（Story 4.3）：不经支付，channel 为 NULL、金额 0。 */
    ADMIN_GRANT
}
