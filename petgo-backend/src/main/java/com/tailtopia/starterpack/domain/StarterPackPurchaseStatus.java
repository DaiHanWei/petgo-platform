package com.tailtopia.starterpack.domain;

/** 礼包购买状态（落库 varchar + CHECK {@code ck_starter_pack_purchases_status}）。 */
public enum StarterPackPurchaseStatus {
    /** QRIS 待付（PawCoin 同一事务内即转 PAID）。 */
    PENDING,
    /** 已付（扣币 / 到账）；发放完成看 {@code fulfilled_at}。 */
    PAID,
    /** QRIS 付款窗已过（之后才到账照样转 PAID 并发放，D-24）。 */
    EXPIRED,
    /** QRIS 出码 / 付款失败。 */
    FAILED
}
