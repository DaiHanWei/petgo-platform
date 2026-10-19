package com.tailtopia.share.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.share.domain.TailsonalityShareReward;
import com.tailtopia.share.repository.TailsonalityShareRewardRepository;
import com.tailtopia.tailsonality.service.TailsonalityShareEligibilityQuery;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · AC2 / X17）：Tailsonality / 护照卡分享奖励去重 = <b>宠物 × 卡类型</b>，
 * 宠物按请求解析：A、B 各一次；同只同卡第二次 0；无宠物 0；他人宠物 0。以 Tailsonality 渠道为代表
 * （两渠道共用 {@link PetCardShareRewardChannel} 流程）。
 */
class PetCardShareRewardChannelTest {

    private static final long USER = 7L;
    private static final long PET_A = 10L;
    private static final long PET_B = 11L;
    private static final long COINS = 100L;

    private TailsonalityShareRewardRepository rewards;
    private PetProfileRepository profiles;
    private ShareRewardService shareReward;
    private TailsonalityShareRewardService service;
    private final Set<String> rewarded = new HashSet<>();

    @BeforeEach
    void setUp() {
        rewards = mock(TailsonalityShareRewardRepository.class);
        profiles = mock(PetProfileRepository.class);
        shareReward = mock(ShareRewardService.class);
        TailsonalityShareEligibilityQuery eligibility = mock(TailsonalityShareEligibilityQuery.class);
        PlatformConfigService platformConfig = mock(PlatformConfigService.class);
        PawCoinConfig cfg = mock(PawCoinConfig.class);
        when(platformConfig.pawcoin()).thenReturn(cfg);
        when(cfg.getTailsonalityShareReward()).thenReturn(COINS);
        when(cfg.getTailsonalityShareDailyCap()).thenReturn(10);
        service = new TailsonalityShareRewardService(rewards, eligibility, profiles, platformConfig, shareReward,
                mock(PlatformTransactionManager.class), mock(StringRedisTemplate.class));
        // 日上限前的 advisory 锁走原生 SQL：单测给一个深桩 EntityManager。
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class, RETURNS_DEEP_STUBS));

        when(eligibility.hasResult(anyLong())).thenReturn(true);
        when(eligibility.hasOwnerType(USER)).thenReturn(true);
        when(profiles.existsByIdAndOwnerId(PET_A, USER)).thenReturn(true);
        when(profiles.existsByIdAndOwnerId(PET_B, USER)).thenReturn(true);
        when(rewards.findByPetProfileIdAndCardType(anyLong(), anyString())).thenAnswer(inv ->
                rewarded.contains(inv.getArgument(0) + ":" + inv.getArgument(1))
                        ? Optional.of(mock(TailsonalityShareReward.class)) : Optional.empty());
        when(rewards.saveAndFlush(any(TailsonalityShareReward.class))).thenAnswer(inv -> {
            TailsonalityShareReward r = inv.getArgument(0);
            rewarded.add(r.getPetProfileId() + ":" + ReflectionTestUtils.getField(r, "cardType"));
            return r;
        });
        when(shareReward.tryReward(eq(USER), anyLong(), anyString(), any(), anyString(), any())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private long share(Long petId, TailsonalityShareRewardService.CardType t) {
        return service.rewardAfterShare(USER, petId, t, Instant.now());
    }

    @Test
    void dedupIsPetTimesCardType() {
        assertThat(share(PET_A, TailsonalityShareRewardService.CardType.RESULT)).isEqualTo(COINS);
        assertThat(share(PET_B, TailsonalityShareRewardService.CardType.RESULT)).as("另一只各一次").isEqualTo(COINS);
        assertThat(share(PET_A, TailsonalityShareRewardService.CardType.RESULT)).as("同只同卡二次").isZero();
        assertThat(share(PET_A, TailsonalityShareRewardService.CardType.MATCH)).as("同只另一卡类型").isEqualTo(COINS);
    }

    @Test
    void noPetOrForeignPetIsZero() {
        assertThat(share(null, TailsonalityShareRewardService.CardType.RESULT)).isZero();
        assertThat(share(99L, TailsonalityShareRewardService.CardType.RESULT)).isZero();
        verify(rewards, never()).saveAndFlush(any());
    }
}
