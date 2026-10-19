package com.tailtopia.growth.domain;

/** 消费返档位（V1.4.0 ① Story 1.3 · 架构 AD-4，{@code growth_spend_tiers.tier}）。 */
public enum SpendTier {
    /** 数字服务：{@link RuleCode#SPEND_DIGITAL}。未登记的用途默认归入此档。 */
    DIGITAL,
    /** 电商：{@link RuleCode#SPEND_SHOP}。 */
    TOKO,
    /** 不返（PawCoin 充值）。 */
    NONE,
    /** 送礼：只走送礼规则，不走消费返。 */
    GIFT;

    /** 该档对应的入账规则；不入账的档返回 null。 */
    public RuleCode rule() {
        return switch (this) {
            case DIGITAL -> RuleCode.SPEND_DIGITAL;
            case TOKO -> RuleCode.SPEND_SHOP;
            case NONE, GIFT -> null;
        };
    }
}
