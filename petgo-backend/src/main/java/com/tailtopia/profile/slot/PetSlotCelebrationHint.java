package com.tailtopia.profile.slot;

import com.tailtopia.growth.service.CelebrationPetSlotHint;
import org.springframework.stereotype.Component;

/**
 * 升级庆祝的永久名额提示（V1.4.0 multi-pet Story 4.4 · AD-16 · FR-146.6 · AC13a）：
 * {@code levelSlots(to) > levelSlots(from) && 此刻有生效中的月租名额}。读时计算、不落库。
 *
 * <p>门槛只经 {@link PetSlotService#levelSlotsAtLevel}（AD-5，① 不自写 5 / 10）；「有生效中月租」取
 * {@link PetSlotService#quotaOf} 的 {@code hasActiveRental}（{@code ACTIVE AND expires_at > now}，RENT 与运营补发同算）。
 * 先比等级、不跨门槛直接 false，不查名额。
 */
@Component
public class PetSlotCelebrationHint implements CelebrationPetSlotHint {

    private final PetSlotService slots;

    public PetSlotCelebrationHint(PetSlotService slots) {
        this.slots = slots;
    }

    @Override
    public boolean petSlotHint(long userId, int fromLevel, int toLevel) {
        if (PetSlotService.levelSlotsAtLevel(toLevel) <= PetSlotService.levelSlotsAtLevel(fromLevel)) {
            return false;
        }
        return slots.quotaOf(userId).hasActiveRental();
    }
}
