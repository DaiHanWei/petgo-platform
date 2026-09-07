package com.tailtopia.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.share.service.IdCardShareRewardService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：分享奖励的**展示口径**（产品 2026-08-27）。
 *
 * <h2>为什么这条重要</h2>
 * 分享奖励三个数**默认全是 0** —— 功能随版本上线，但默认一分不发，等运营在后台把数配上
 * 才开始发（见 `chore(share): 分享奖励默认值改为 0`）。
 * 于是卡面页上凡是提到「分享可得 PawCoin」的文案，都必须**配好了才显示**，
 * 否则就是 Story 18.2 AC6 反复要避免的那件事：<b>承诺了奖励却不发</b>。
 *
 * <p>🔴 判据只有「配没配 + 你还有没有资格」，**不看月度额度** ——
 * AC3 不许把「额度用完了」讲给用户听（会诱导攒着别分享 / 月初集中刷满）。
 * 额度耗尽时这句文案会短暂过承诺，是有意接受的代价。
 */
class IdCardShareRewardAdvertiseIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private IdCardShareRewardService rewards;

    @Autowired
    private PlatformConfigService platformConfig;

    @Autowired
    private PawCoinConfigRepository pawcoinRepo;

    private void configure(boolean enabled, long perShare, int dailyCap) {
        var cfg = platformConfig.pawcoin();
        cfg.setShareRewardEnabled(enabled);
        cfg.setIdCardShareReward(perShare);
        cfg.setIdCardShareDailyCap(dailyCap);
        pawcoinRepo.save(cfg);
    }

    /** 🔴 默认三个 0 的出厂状态：一个字都不该提。 */
    @Test
    void nothingIsAdvertisedWhenRewardIsNotConfigured() {
        configure(true, 0, 0);
        assertThat(rewards.advertisableCoins(1L))
                .as("🔴 没配奖励却把「分享可得 PawCoin」讲出去 = 承诺了却不发")
                .isZero();
    }

    /** 总开关关掉（运营发现被刷时的紧急动作）→ 文案必须同时消失。 */
    @Test
    void nothingIsAdvertisedWhenTheMasterSwitchIsOff() {
        configure(false, 20, 3);
        assertThat(rewards.advertisableCoins(1L))
                .as("总开关关了文案还在 ⇒ 关掉开关反而变成「承诺了却不发」")
                .isZero();
    }

    /**
     * 🛡 日上限为 0 同样发不出来（发放链路里那道闸），所以也不该承诺。
     * 只看「每次发放数」会漏掉这一种配错。
     */
    @Test
    void nothingIsAdvertisedWhenDailyCapIsZero() {
        configure(true, 20, 0);
        assertThat(rewards.advertisableCoins(1L)).isZero();
    }

    /** 配好了、但这个用户没有宠物档案 → 本来就不发（AC4），别承诺。 */
    @Test
    void nothingIsAdvertisedWithoutAPetProfile() {
        configure(true, 20, 3);
        assertThat(rewards.advertisableCoins(999_999L))
                .as("无档案本来就不发（AC4）")
                .isZero();
    }
}
