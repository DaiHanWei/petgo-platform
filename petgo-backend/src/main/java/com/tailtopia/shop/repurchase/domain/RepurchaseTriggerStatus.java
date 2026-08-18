package com.tailtopia.shop.repurchase.domain;

/** 复购触发状态（Story 6.3）。 */
public enum RepurchaseTriggerStatus {
    /** 进行中。同一 (用户, SKU) 至多一条（库级部分唯一索引）。 */
    ACTIVE,
    /** 🔴 用户再次购买该 SKU → 旧触发<b>立即失效</b>，按新订单重新起算。 */
    SUPERSEDED,
    /** 用户手动关掉了这张卡。 */
    DISMISSED,
    /** 用户从这张卡完成了购买（AB-13B 判定 A-16 的分子）。 */
    CONVERTED
}
