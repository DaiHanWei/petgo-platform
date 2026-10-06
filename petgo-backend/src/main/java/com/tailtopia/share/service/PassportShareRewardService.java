package com.tailtopia.share.service;

import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.passport.service.PassportShareEligibilityQuery;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.share.domain.PassportShareReward;
import com.tailtopia.share.repository.PassportShareRewardRepository;
import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 护照卡 / 登机牌卡分享奖励的**渠道层**发放（V1.3.2 Story 4.5 · AD-13）。
 *
 * <p>🔴 每只宠物每卡类型只发一次（PAGE、BOARDING 各一次）；**登机牌整体一个卡类型，不按张计**
 * （接口不收场所 token —— 按张计会让「打卡数 = 奖励数」）；带水印卡同样计。
 * 流程与闸门顺序见 {@link PetCardShareRewardChannel}。
 */
@Service
public class PassportShareRewardService extends PetCardShareRewardChannel<PassportShareRewardService.CardType> {

    /** 卡类型（= 表 {@code card_type} 取值）。 */
    public enum CardType {
        PAGE, BOARDING
    }

    static final String REF_TYPE = "PASSPORT_SHARE";
    static final String CHANNEL_PREFIX = "passport-share:";

    /** 日上限 advisory 锁命名空间；不得与 {@link AgeCardShareRewardService#DAILY_CAP_LOCK_NS} 等相撞。 */
    static final int DAILY_CAP_LOCK_NS = 0x5050_5352; // "PPSR"

    private final PassportShareRewardRepository rewards;
    private final PassportShareEligibilityQuery eligibility;

    public PassportShareRewardService(PassportShareRewardRepository rewards,
            PassportShareEligibilityQuery eligibility, PetProfileRepository profiles,
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
        return cfg.getPassportShareReward();
    }

    @Override
    int dailyCapOf(PawCoinConfig cfg) {
        return cfg.getPassportShareDailyCap();
    }

    /** PAGE → 已签发护照且至少 1 枚章；BOARDING → 至少 1 条场所打卡。 */
    @Override
    boolean eligible(long userId, long petProfileId, CardType cardType) {
        return cardType == CardType.PAGE
                ? eligibility.canSharePage(petProfileId)
                : eligibility.canShareBoarding(petProfileId);
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
        rewards.saveAndFlush(PassportShareReward.of(petProfileId, userId, cardType.name(), coins, day));
    }
}
