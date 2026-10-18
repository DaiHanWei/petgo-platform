package com.tailtopia.profile.slot;

/**
 * 账号的宠物名额（V1.4.0 multi-pet AD-5，实时计算、不落库）。只由 {@link PetSlotService#quotaOf} 产出。
 *
 * @param total           名额 = min(5, 默认 + 等级 + 月租)
 * @param defaultSlots    默认名额（恒 1）
 * @param levelSlots      等级名额（参与成长体系时 [Lv≥5] + [Lv≥10]，不参与 0）
 * @param rentalSlots     生效中的月租名额（Story 2.2 接入，本 story 恒 0）
 * @param petCount        已建宠物只数
 * @param rentable        还能租的名额数 = 5 − total（与已建只数无关）
 * @param canRent         {@code rentable > 0}
 * @param nextLevelSlot   下一个解锁名额的等级（5 / 10），没有则 null（含不参与）
 * @param hasActiveRental 是否有生效中的月租（Story 2.2 接入，本 story 恒 false）
 */
public record PetSlotQuota(
        int total,
        int defaultSlots,
        int levelSlots,
        int rentalSlots,
        int petCount,
        int rentable,
        boolean canRent,
        Integer nextLevelSlot,
        boolean hasActiveRental) {
}
