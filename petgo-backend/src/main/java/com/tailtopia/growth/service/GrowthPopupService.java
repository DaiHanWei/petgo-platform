package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthBenefit;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse.BackfillView;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse.CelebrationView;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse.FrameAction;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 升级庆祝 / 存量汇总弹窗的投递（V1.4.0 ① Story 2.1 · AD-11）。
 *
 * <p>写入纪律（§5.4）：本类只动 {@code celebrate_from_level / celebrate_to_level / backfill_popup_pending} 三列，
 * 不碰累计与等级；写之前锁账户行（与入账串行）。
 *
 * <p>读时校正：降级后才拉到的待弹按 {@code min(to, 当前等级)} 计算；不高于 from 视为无待弹并清掉两列（降级不弹窗）。
 *
 * <p>{@code petSlotHint}（multi-pet Story 4.4 · AD-16）：经可选的 {@link CelebrationPetSlotHint}（多宠物线实现）按校正后的
 * from / to 读时计算；缺实现或判定抛错 → {@code false}（提示只是锦上添花，不得挡住庆祝弹窗）。
 */
@Service
public class GrowthPopupService {

    private static final Logger log = LoggerFactory.getLogger(GrowthPopupService.class);

    private final GrowthAccountRepository accounts;
    private final GrowthLevelDefRepository levels;
    private final GrowthPageQuery page;
    private final Supplier<CelebrationPetSlotHint> petSlotHint;
    private final Clock clock;

    @Autowired
    public GrowthPopupService(GrowthAccountRepository accounts, GrowthLevelDefRepository levels,
            GrowthPageQuery page, ObjectProvider<CelebrationPetSlotHint> petSlotHint) {
        this(accounts, levels, page, petSlotHint::getIfAvailable, Clock.systemUTC());
    }

    GrowthPopupService(GrowthAccountRepository accounts, GrowthLevelDefRepository levels, GrowthPageQuery page,
            Clock clock) {
        this(accounts, levels, page, () -> null, clock);
    }

    GrowthPopupService(GrowthAccountRepository accounts, GrowthLevelDefRepository levels, GrowthPageQuery page,
            Supplier<CelebrationPetSlotHint> petSlotHint, Clock clock) {
        this.accounts = accounts;
        this.levels = levels;
        this.page = page;
        this.petSlotHint = petSlotHint;
        this.clock = clock;
    }

    @Transactional
    public GrowthPopupsResponse getPopups(long userId) {
        if (!page.participating(userId)) {
            return GrowthPopupsResponse.none(); // 虚拟号 / 官方号 / 停用：无等级页与庆祝
        }
        Optional<GrowthAccount> found = accounts.findById(userId);
        if (found.isEmpty()) {
            return GrowthPopupsResponse.none();
        }
        GrowthAccount a = found.get();
        CelebrationView celebration = null;
        if (a.getCelebrateFromLevel() != null && a.getCelebrateToLevel() != null) {
            celebration = celebration(a);
        }
        return new GrowthPopupsResponse(celebration, backfill(a));
    }

    /**
     * 存量汇总（Story 2.2）：{@code backfill_popup_pending = true} 才返回。天数 / 里程碑读补算当时的快照列
     * （{@code user_active_days} 上线后还在增长，现算会对不上当时入账值）；{@code level / tier} 为当前等级与段位。
     */
    BackfillView backfill(GrowthAccount a) {
        if (!a.isBackfillPopupPending()) {
            return null;
        }
        long days = a.getBackfillActiveDays() == null ? 0 : a.getBackfillActiveDays();
        long count = a.getBackfillMilestoneCount() == null ? 0 : a.getBackfillMilestoneCount();
        long exp = a.getBackfillMilestoneExp() == null ? 0 : a.getBackfillMilestoneExp();
        GrowthTier tier = levels.findById(a.getLevel()).map(GrowthLevelDef::getTier).orElse(GrowthTier.NEWBIE);
        return new BackfillView(a.getLevel(), tier, days, count, exp, days * 3 + exp);
    }

    private CelebrationView celebration(GrowthAccount a) {
        int from = a.getCelebrateFromLevel();
        int effectiveTo = Math.min(a.getCelebrateToLevel(), a.getLevel());
        if (effectiveTo <= from) {
            // 降级后才拉到：视为无待弹并清列（条件 UPDATE，避免拿过时实体覆盖并发入账刚写的待弹）。
            accounts.clearStaleCelebration(a.getUserId());
            return null;
        }
        Map<Integer, GrowthTier> tiers = levels.findAllByOrderByLevelAsc().stream()
                .collect(Collectors.toMap(GrowthLevelDef::getLevel, GrowthLevelDef::getTier));
        GrowthTier fromTier = tiers.getOrDefault(from, GrowthTier.NEWBIE);
        GrowthTier toTier = tiers.getOrDefault(effectiveTo, fromTier);
        boolean tierUp = fromTier != toTier;
        List<GrowthBenefit> benefits = tierUp ? GrowthPageQuery.BENEFITS.getOrDefault(toTier, List.of()) : List.of();
        return new CelebrationView(from, effectiveTo, tierUp, toTier, a.getLifetimeTotal(), benefits,
                frameAction(tierUp, toTier, a.getFrameChoice()), petSlotHint(a.getUserId(), from, effectiveTo));
    }

    /** 用校正后的 from / to（与返回的 fromLevel / toLevel 同一对数）。 */
    private boolean petSlotHint(long userId, int from, int to) {
        CelebrationPetSlotHint hint = petSlotHint.get();
        if (hint == null) {
            return false;
        }
        try {
            return hint.petSlotHint(userId, from, to);
        } catch (RuntimeException ex) {
            log.warn("celebration petSlotHint failed: userId={} error={}", userId, ex.getClass().getSimpleName());
            return false;
        }
    }

    static FrameAction frameAction(boolean tierUp, GrowthTier toTier, FrameChoice choice) {
        if (!tierUp) {
            return FrameAction.NONE;
        }
        if (choice == null) {
            return FrameAction.AUTO;
        }
        return toTier != GrowthTier.NEWBIE ? FrameAction.OFFER_NEW_FRAME : FrameAction.NONE;
    }

    /**
     * 确认升级庆祝。{@code toLevel}（App 弹出时看到的 to）落在 {@code (from, to)} 之间 → 弹出期间又升了级：只把 from
     * 推进到 toLevel，下次弹「toLevel → 新等级」；{@code toLevel <= from}（过时的确认，不对应当前待弹）→ 不动；
     * 其余（含不带参数）清两列。幂等（无待弹空操作）。
     */
    @Transactional
    public void ackCelebration(long userId, Integer toLevel) {
        accounts.lockById(userId).ifPresent(a -> {
            Integer from = a.getCelebrateFromLevel();
            Integer to = a.getCelebrateToLevel();
            if (from == null && to == null) {
                return;
            }
            if (toLevel != null && from != null && toLevel <= from) {
                return; // 客户端拿着更早的一次弹窗来确认：当前待弹它没见过，保留
            }
            if (toLevel != null && to != null && to > toLevel) {
                a.advanceCelebrationFrom(toLevel, clock.instant());
            } else {
                a.clearCelebration(clock.instant());
            }
        });
    }

    /** 确认存量汇总弹窗。幂等（无账户行 / 已确认空操作）。 */
    @Transactional
    public void ackBackfill(long userId) {
        accounts.lockById(userId).ifPresent(a -> {
            if (a.isBackfillPopupPending()) {
                a.clearBackfillPopup(clock.instant());
            }
        });
    }
}
