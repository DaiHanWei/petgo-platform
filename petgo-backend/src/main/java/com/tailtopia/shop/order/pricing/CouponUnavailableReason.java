package com.tailtopia.shop.order.pricing;

/** 券在本单不可用的原因（Story 1.2，D-7）。 */
public enum CouponUnavailableReason {
    /** 未达门槛（满减：范围内券前金额；免运：商品实付） */
    BELOW_MIN_SPEND,
    /** 范围内无商品（满减）/ 有商品不在范围（免运） */
    OUT_OF_SCOPE,
    /** 不在有效期内（含尚未生效） */
    EXPIRED,
    /** 免运券：满额免运减得不比它少（相等用满额免运） */
    RULE_BETTER
}
