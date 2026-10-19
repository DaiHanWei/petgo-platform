package com.tailtopia.profile.slot;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物名额计算（V1.4.0 multi-pet AD-5 · FR-146.1）：名额 = min(5, 1 + [Lv≥5] + [Lv≥10] + 生效月租数)，实时、不落库。
 *
 * <p>🔴 名额<b>只经本方法</b>（{@link #quotaOf}，架构 §5）：别处不得自拼「1 + 等级」。
 * 月租部分（Story 2.2）= {@code pet_slot_rentals} 中 {@code status = ACTIVE AND expires_at > now} 的行数（RENT 与 ADMIN_GRANT
 * 都算）；构成里的 {@code rentalSlots} 给封顶后的<b>有效</b>值 {@code total − 1 − levelSlots}（多出的本期不退、不显示为闲置）。
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

    /**
     * 月租单价的迁移种子值（IDR，{@code V20261018_1602}）。Story 2.2 起实际单价一律经 {@link #unitPrice()} 读
     * {@code pricing_config.pet_slot_monthly_price}，本常量只作文档 / 测试参照。
     */
    public static final long DEFAULT_UNIT_PRICE = 5000L;

    /** 一次月租的天数。 */
    public static final int RENTAL_DAYS = 30;

    private final GrowthQueryService growth;
    private final PetProfileRepository profiles;
    private final PetSlotRentalRepository rentals;
    private final PlatformConfigService platformConfig;
    private final Clock clock;

    @Autowired
    public PetSlotService(GrowthQueryService growth, PetProfileRepository profiles, PetSlotRentalRepository rentals,
            PlatformConfigService platformConfig) {
        this(growth, profiles, rentals, platformConfig, Clock.systemUTC());
    }

    PetSlotService(GrowthQueryService growth, PetProfileRepository profiles, PetSlotRentalRepository rentals,
            PlatformConfigService platformConfig, Clock clock) {
        this.growth = growth;
        this.profiles = profiles;
        this.rentals = rentals;
        this.platformConfig = platformConfig;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PetSlotQuota quotaOf(long userId) {
        GrowthLevel level = growth.getLevel(userId);
        int levelSlots = levelSlots(level);
        Integer nextLevelSlot = nextLevelSlot(level);
        long liveRentals = rentals.countByUserIdAndStatusAndExpiresAtAfter(userId, PetSlotRentalStatus.ACTIVE,
                Instant.now(clock));
        boolean hasActiveRental = liveRentals > 0;
        int total = (int) Math.min(MAX_PETS, DEFAULT_SLOTS + levelSlots + liveRentals);
        int rentalSlots = total - DEFAULT_SLOTS - levelSlots; // 有效值：封顶后多出的月租不显示为闲置
        int petCount = (int) profiles.countByOwnerId(userId);
        int rentable = MAX_PETS - total;
        return new PetSlotQuota(total, DEFAULT_SLOTS, levelSlots, rentalSlots, petCount, rentable,
                rentable > 0, nextLevelSlot, hasActiveRental);
    }

    /** 等级名额（[Lv≥5] + [Lv≥10]；不参与 = 0）。与 {@link #quotaOf} 同一口径，供到账发放时的封顶校验（Story 2.2 复审 #2）。 */
    @Transactional(readOnly = true)
    public int levelSlotsOf(long userId) {
        return levelSlots(growth.getLevel(userId));
    }

    /**
     * 某个等级带来的等级名额数（[Lv≥5] + [Lv≥10]，按等级本身算、不判参与）。供升级庆祝的永久名额提示比较
     * from / to 两个等级（Story 4.4 · AD-16）；本类内唯一一处门槛口径，{@link #quotaOf} 同样经它。
     */
    public static int levelSlotsAtLevel(int level) {
        int n = 0;
        for (int threshold : LEVEL_SLOT_THRESHOLDS) {
            if (level >= threshold) {
                n++;
            }
        }
        return n;
    }

    private static int levelSlots(GrowthLevel level) {
        if (level == null || !level.participating()) {
            return 0;
        }
        return levelSlotsAtLevel(level.level());
    }

    private static Integer nextLevelSlot(GrowthLevel level) {
        if (level == null || !level.participating()) {
            return null;
        }
        for (int threshold : LEVEL_SLOT_THRESHOLDS) {
            if (level.level() < threshold) {
                return threshold;
            }
        }
        return null;
    }

    /** 月租单价（IDR / 名额 / 30 天）：每次实时读 {@code pricing_config.pet_slot_monthly_price}（Story 2.2 · AD-6）。 */
    public long unitPrice() {
        return platformConfig.pricing().getPetSlotMonthlyPrice();
    }

    /** 续租个数上限 = max(0, 5 − 1 − 等级名额)（PRD：租用期间升级超 5，到期后只能续到「5 − 等级名额」个）。 */
    public static int renewableMax(PetSlotQuota quota) {
        return Math.max(0, MAX_PETS - DEFAULT_SLOTS - quota.levelSlots());
    }

    /** 生效中的月租名额（{@code ACTIVE AND expires_at > now}），按 {@code starts_at, id} 升序。 */
    @Transactional(readOnly = true)
    public List<PetSlotRental> activeRentals(long userId) {
        return rentals.findByUserIdAndStatusAndExpiresAtAfterOrderByStartsAtAscIdAsc(userId,
                PetSlotRentalStatus.ACTIVE, Instant.now(clock));
    }

    /** 账号只读宠物只数（名额管理页 {@code readOnlyCount}）。 */
    @Transactional(readOnly = true)
    public int readOnlyPetCount(long userId) {
        return (int) profiles.countByOwnerIdAndStatus(userId, PetStatus.READ_ONLY);
    }

    /** 本服务的「现在」（名额管理页算 {@code daysLeft} 用，与 {@link #quotaOf} 同一时钟）。 */
    public Instant now() {
        return Instant.now(clock);
    }
}
