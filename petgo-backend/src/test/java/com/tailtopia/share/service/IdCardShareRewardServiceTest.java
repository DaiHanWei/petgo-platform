package com.tailtopia.share.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.share.domain.IdCardShareReward;
import com.tailtopia.share.repository.IdCardShareRewardRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · AC2 / X15 / X16）：身份证分享奖励按<b>请求的宠物</b>各发一次；
 * 同一只第二次 0；无宠物 0；账号级月度上限（{@link ShareRewardService#tryReward}）拦下另一只。
 * 纯 Mockito：留痕表用内存集合模拟唯一约束，事务管理器为 mock。
 */
class IdCardShareRewardServiceTest {

    private static final long USER = 7L;
    private static final long CARD = 5L;
    private static final long PET_A = 10L;
    private static final long PET_B = 11L;
    private static final long COINS = 200L;

    private IdCardShareRewardRepository rewards;
    private PetProfileRepository profiles;
    private IdCardRepository cards;
    private ShareRewardService shareReward;
    private PawCoinConfig cfg;
    private IdCardShareRewardService service;
    private final Set<Long> rewardedPets = new HashSet<>();
    private final AtomicLong monthlyGranted = new AtomicLong();
    private long monthlyCap = 10_000;

    @BeforeEach
    void setUp() {
        rewards = mock(IdCardShareRewardRepository.class);
        profiles = mock(PetProfileRepository.class);
        cards = mock(IdCardRepository.class);
        shareReward = mock(ShareRewardService.class);
        PlatformConfigService platformConfig = mock(PlatformConfigService.class);
        cfg = mock(PawCoinConfig.class);
        when(platformConfig.pawcoin()).thenReturn(cfg);
        when(cfg.isShareRewardEnabled()).thenReturn(true);
        when(cfg.getIdCardShareReward()).thenReturn(COINS);
        when(cfg.getIdCardShareDailyCap()).thenReturn(10);
        when(cfg.getShareRewardMonthlyCap()).thenReturn(10_000L);
        service = new IdCardShareRewardService(rewards, profiles, cards, platformConfig, shareReward,
                mock(PlatformTransactionManager.class), mock(StringRedisTemplate.class));

        when(cards.findByIdAndUserId(CARD, USER)).thenReturn(Optional.of(mock(IdCard.class)));
        when(profiles.existsByIdAndOwnerId(PET_A, USER)).thenReturn(true);
        when(profiles.existsByIdAndOwnerId(PET_B, USER)).thenReturn(true);
        when(rewards.findByPetProfileId(anyLong())).thenAnswer(inv ->
                rewardedPets.contains(inv.<Long>getArgument(0)) ? Optional.of(mock(IdCardShareReward.class))
                        : Optional.empty());
        when(rewards.saveAndFlush(any(IdCardShareReward.class))).thenAnswer(inv -> {
            IdCardShareReward r = inv.getArgument(0);
            rewardedPets.add(r.getPetProfileId());
            return r;
        });
        when(shareReward.tryReward(eq(USER), anyLong(), anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            long coins = inv.getArgument(1);
            if (monthlyGranted.get() + coins > monthlyCap) {
                return false;
            }
            monthlyGranted.addAndGet(coins);
            return true;
        });
        // attempt 内登记事务同步器（清理 Redis 幂等键），单测里手动开同步。
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void eachPetIsRewardedOnceAndSecondTimeIsZero() {
        assertThat(service.rewardAfterShare(USER, CARD, PET_A, Instant.now())).isEqualTo(COINS);
        assertThat(service.rewardAfterShare(USER, CARD, PET_B, Instant.now())).as("另一只宠物各一次").isEqualTo(COINS);
        assertThat(service.rewardAfterShare(USER, CARD, PET_A, Instant.now())).as("同一只第二次").isZero();
        assertThat(service.rewardAfterShare(USER, CARD, PET_B, Instant.now())).isZero();
    }

    @Test
    void accountMonthlyCapBlocksTheOtherPet() {
        monthlyCap = COINS; // 账号当月只装得下一次
        assertThat(service.rewardAfterShare(USER, CARD, PET_A, Instant.now())).isEqualTo(COINS);
        assertThat(service.rewardAfterShare(USER, CARD, PET_B, Instant.now()))
                .as("上限沿用账号级月度上限，换宠物不绕过").isZero();
    }

    @Test
    void noPetIsZeroAndForeignPetIsZero() {
        assertThat(service.rewardAfterShare(USER, CARD, null, Instant.now())).isZero();
        assertThat(service.rewardAfterShare(USER, CARD, 99L, Instant.now())).isZero();
        verify(rewards, never()).saveAndFlush(any());
    }

    @Test
    void advertisableCoinsIsPerPet() {
        assertThat(service.advertisableCoins(USER, Optional.of(PET_A))).isEqualTo(COINS);
        rewardedPets.add(PET_A);
        assertThat(service.advertisableCoins(USER, Optional.of(PET_A))).as("这只已领过").isZero();
        assertThat(service.advertisableCoins(USER, Optional.of(PET_B))).as("另一只仍可承诺").isEqualTo(COINS);
        assertThat(service.advertisableCoins(USER, Optional.empty())).as("无档案").isZero();
    }
}
