package com.tailtopia.starterpack.domain;

import com.tailtopia.config.domain.PricingConfig;
import java.util.List;
import java.util.Optional;

/**
 * 礼包三档（V1.5.0 gift-pack Story 4-2 · PRD B §3.1 · D-21）。
 *
 * <p>单买合计 {@code separateTotal = KTP 高清单价 × 8 + Tailsonality 单价 + (含名额 ? 名额月租 : 0) + 礼物次数 × 1000}，
 * 价格与单价全部取 {@link PricingConfig} 当前值（下单时快照进购买行）。
 */
public enum StarterPackTier {
    TIER_1(1),
    TIER_2(2),
    TIER_3(3);

    /** 礼物免费次数对应的单价（1.000 档礼物）。 */
    public static final long GIFT_CREDIT_UNIT_PRICE = 1000L;
    /** KTP 高清次数（8 款各 1）。 */
    public static final int KTP_HD_COUNT = 8;

    private final int number;

    StarterPackTier(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }

    public static Optional<StarterPackTier> of(Integer number) {
        if (number == null) {
            return Optional.empty();
        }
        for (StarterPackTier t : values()) {
            if (t.number == number) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public List<StarterPackContent> contents() {
        return switch (this) {
            case TIER_1 -> List.of(StarterPackContent.KTP_HD_8, StarterPackContent.TAILSONALITY_1);
            case TIER_2 -> List.of(StarterPackContent.KTP_HD_8, StarterPackContent.TAILSONALITY_1,
                    StarterPackContent.PET_SLOT_30D);
            case TIER_3 -> List.of(StarterPackContent.KTP_HD_8, StarterPackContent.TAILSONALITY_1,
                    StarterPackContent.PET_SLOT_30D, StarterPackContent.GIFT_1K_5);
        };
    }

    /** 是否含宠物名额（档 2 / 3）。 */
    public boolean hasSlot() {
        return this != TIER_1;
    }

    /** 1.000 档礼物免费次数（0 / 0 / 5）。 */
    public int giftCredits() {
        return this == TIER_3 ? 5 : 0;
    }

    /** 当前档价（IDR = PawCoin）。 */
    public long price(PricingConfig pricing) {
        return switch (this) {
            case TIER_1 -> pricing.getStarterPackTier1Price();
            case TIER_2 -> pricing.getStarterPackTier2Price();
            case TIER_3 -> pricing.getStarterPackTier3Price();
        };
    }

    /** 单买合计（见类注释）。 */
    public long separateTotal(PricingConfig pricing) {
        long total = Math.multiplyExact(pricing.getIdHdDownloadPrice(), (long) KTP_HD_COUNT)
                + pricing.getTailsonalityUnlockPrice();
        if (hasSlot()) {
            total += pricing.getPetSlotMonthlyPrice();
        }
        return total + Math.multiplyExact((long) giftCredits(), GIFT_CREDIT_UNIT_PRICE);
    }

    /** 省下的钱 = 单买合计 − 档价（运营把价调得高于合计时为负，原样返回由展示侧处理）。 */
    public long saving(PricingConfig pricing) {
        return separateTotal(pricing) - price(pricing);
    }
}
