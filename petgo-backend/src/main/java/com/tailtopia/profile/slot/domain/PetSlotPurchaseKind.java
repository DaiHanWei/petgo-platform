package com.tailtopia.profile.slot.domain;

/** 名额购买类型（V1.4.0 multi-pet Story 2.2 · AD-5，落库 varchar + CHECK）。 */
public enum PetSlotPurchaseKind {
    /** 新租：到账 / 扣币后新建 N 行名额。 */
    RENT,
    /** 续租：所选生效中名额 +30 天。 */
    RENEW,
    /** 运营补发（Story 4.3）：不经支付，channel 为 NULL、金额 0。 */
    ADMIN_GRANT,
    /** V1.5.0 超值礼包发放新名额（D-23）：不经支付，channel 为 NULL、金额 0，关联 {@code starter_pack_purchase_id}。 */
    PACK_GRANT,
    /** V1.5.0 超值礼包延长最早到期的租用名额 +30 天（D-23）：不新建名额，{@code rental_ids} = 被延长的那个。 */
    PACK_EXTEND
}
