package com.tailtopia.pay.domain;

/**
 * PawCoin 充值固定档位（Story 1.3，内置默认）。金额 = koin（1 koin = Rp1，1:1）。
 *
 * <p>本 story 内置 4 档；<b>后台可配是 Story 9.2</b>——届时 {@code TopupTierProvider} 换 DB 实现，
 * 本枚举退为回退默认。
 */
public enum TopupTier {
    TIER_10K("10k", 10_000L),
    TIER_25K("25k", 25_000L),
    TIER_50K("50k", 50_000L),
    TIER_100K("100k", 100_000L);

    private final String id;
    private final long amountIdr;

    TopupTier(String id, long amountIdr) {
        this.id = id;
        this.amountIdr = amountIdr;
    }

    public String getId() {
        return id;
    }

    /** 充值金额（IDR 最小单位整型）。 */
    public long getAmountIdr() {
        return amountIdr;
    }

    /** 到账 koin（1:1）。 */
    public long getCoins() {
        return amountIdr;
    }
}
