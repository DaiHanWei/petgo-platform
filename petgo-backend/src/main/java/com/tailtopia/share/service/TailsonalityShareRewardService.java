package com.tailtopia.share.service;

import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.share.domain.TailsonalityShareReward;
import com.tailtopia.share.repository.TailsonalityShareRewardRepository;
import com.tailtopia.tailsonality.service.TailsonalityShareEligibilityQuery;
import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tailsonality 结果卡 / 配型卡分享奖励的**渠道层**发放（V1.3.2 Story 4.5 · AD-13）。
 *
 * <p>🔴 每只宠物每卡类型只发一次（RESULT、MATCH 各一次）；带水印卡同样计（接口不收、不看水印态）；
 * 重测后再分享不再发（键是宠物不是结果）。流程与闸门顺序见 {@link PetCardShareRewardChannel}。
 */
@Service
public class TailsonalityShareRewardService extends PetCardShareRewardChannel<TailsonalityShareRewardService.CardType> {

    /** 卡类型（= 表 {@code card_type} 取值）。 */
    public enum CardType {
        RESULT, MATCH
    }

    static final String REF_TYPE = "TAILSONALITY_SHARE";
    static final String CHANNEL_PREFIX = "tailsonality-share:";

    /** 日上限 advisory 锁命名空间；不得与 {@link AgeCardShareRewardService#DAILY_CAP_LOCK_NS} 等相撞。 */
    static final int DAILY_CAP_LOCK_NS = 0x5453_5352; // "TSSR"

    private final TailsonalityShareRewardRepository rewards;
    private final TailsonalityShareEligibilityQuery eligibility;

    public TailsonalityShareRewardService(TailsonalityShareRewardRepository rewards,
            TailsonalityShareEligibilityQuery eligibility, PetProfileRepository profiles,
            PlatformConfigService platformConfig, ShareRewardService shareReward,
            PlatformTransactionManager txManager, StringRedisTemplate redis) {
        super(profiles, platformConfig, shareReward, txManager, redis);
        this.rewards = rewards;
        this.eligibility = eligibility;
    }

    @Override
    String refType() {
        return REF_TYPE;
    }

    @Override
    String idemPrefix() {
        return CHANNEL_PREFIX;
    }

    @Override
    int dailyCapLockNamespace() {
        return DAILY_CAP_LOCK_NS;
    }

    @Override
    long rewardOf(PawCoinConfig cfg) {
        return cfg.getTailsonalityShareReward();
    }

    @Override
    int dailyCapOf(PawCoinConfig cfg) {
        return cfg.getTailsonalityShareDailyCap();
    }

    /** RESULT / MATCH → 至少一条结果；MATCH 另需本人已设主人类型。 */
    @Override
    boolean eligible(long userId, long petProfileId, CardType cardType) {
        if (!eligibility.hasResult(petProfileId)) {
            return false;
        }
        return cardType != CardType.MATCH || eligibility.hasOwnerType(userId);
    }

    @Override
    boolean alreadyRewarded(long petProfileId, CardType cardType) {
        return rewards.findByPetProfileIdAndCardType(petProfileId, cardType.name()).isPresent();
    }

    @Override
    long countOn(long userId, LocalDate day) {
        return rewards.countByUserIdAndShareDate(userId, day);
    }

    @Override
    void record(long petProfileId, long userId, CardType cardType, long coins, LocalDate day) {
        rewards.saveAndFlush(TailsonalityShareReward.of(petProfileId, userId, cardType.name(), coins, day));
    }
}
