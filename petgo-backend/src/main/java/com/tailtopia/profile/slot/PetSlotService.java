package com.tailtopia.profile.slot;

import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.repository.PetProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物名额计算（V1.4.0 multi-pet AD-5 · FR-146.1）：名额 = min(5, 1 + [Lv≥5] + [Lv≥10] + 生效月租数)，实时、不落库。
 *
 * <p>🔴 名额<b>只经本方法</b>（{@link #quotaOf}，架构 §5）：别处不得自拼「1 + 等级」。
 * 月租部分由 Story 2.2 接入（{@code pet_slot_rentals}），本 story {@code rentalSlots} 恒 0、{@code hasActiveRental} 恒 false。
 * 等级只读 {@link GrowthQueryService#getLevel}（地基契约，不自写等级判断）：① 替换其方法体后本方法自动读到真实等级；
 * {@code participating = false}（虚拟号 / 查无此人）一律不给等级名额。
 */
@Service
public class PetSlotService {

    /** 每账号宠物只数硬上限（PRD 拍板）。 */
    public static final int MAX_PETS = 5;

    /** 默认名额。 */
    public static final int DEFAULT_SLOTS = 1;

    /** 每达到一个门槛等级加 1 个永久名额（PRD 拍板值，本类内唯一一处）。 */
    static final int[] LEVEL_SLOT_THRESHOLDS = {5, 10};

    /** 月租单价（IDR）。本 story 为常量，Story 2.2 改读 {@code pricing_config.pet_slot_monthly_price}。 */
    public static final long DEFAULT_UNIT_PRICE = 5000L;

    /** 一次月租的天数。 */
    public static final int RENTAL_DAYS = 30;

    private final GrowthQueryService growth;
    private final PetProfileRepository profiles;

    public PetSlotService(GrowthQueryService growth, PetProfileRepository profiles) {
        this.growth = growth;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public PetSlotQuota quotaOf(long userId) {
        GrowthLevel level = growth.getLevel(userId);
        boolean participating = level != null && level.participating();
        int levelSlots = 0;
        Integer nextLevelSlot = null;
        if (participating) {
            for (int threshold : LEVEL_SLOT_THRESHOLDS) {
                if (level.level() >= threshold) {
                    levelSlots++;
                } else if (nextLevelSlot == null) {
                    nextLevelSlot = threshold;
                }
            }
        }
        int rentalSlots = 0; // Story 2.2 接入 pet_slot_rentals
        boolean hasActiveRental = false; // Story 2.2 接入
        int total = Math.min(MAX_PETS, DEFAULT_SLOTS + levelSlots + rentalSlots);
        int petCount = (int) profiles.countByOwnerId(userId);
        int rentable = MAX_PETS - total;
        return new PetSlotQuota(total, DEFAULT_SLOTS, levelSlots, rentalSlots, petCount, rentable,
                rentable > 0, nextLevelSlot, hasActiveRental);
    }

    /** 月租单价（IDR）。本 story 恒 {@link #DEFAULT_UNIT_PRICE}；Story 2.2 改读价格配置。 */
    public long unitPrice() {
        return DEFAULT_UNIT_PRICE;
    }
}
