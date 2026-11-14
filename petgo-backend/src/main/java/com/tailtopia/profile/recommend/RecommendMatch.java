package com.tailtopia.profile.recommend;

/**
 * 推荐卡命中哪一段（V1.5.0 onboarding-recall Story 1.1 · OR-4，埋点用）。
 *
 * <p>与游标段号一一对应：段 0 → {@link #SAME_BREED}、段 1 → {@link #SAME_SPECIES}、段 2 → {@link #GLOBAL}。
 * 没宠物 / 游客只有全站段，恒 {@link #GLOBAL}。
 */
public enum RecommendMatch {
    SAME_BREED,
    SAME_SPECIES,
    GLOBAL;

    static RecommendMatch ofTier(int tier) {
        return switch (tier) {
            case 0 -> SAME_BREED;
            case 1 -> SAME_SPECIES;
            default -> GLOBAL;
        };
    }
}
