package com.tailtopia.share.service;

import com.tailtopia.share.repository.AgeCardShareRewardRepository;
import com.tailtopia.share.repository.IdCardShareRewardRepository;
import com.tailtopia.share.repository.PassportShareRewardRepository;
import com.tailtopia.share.repository.ShareRewardQuotaRepository;
import com.tailtopia.share.repository.TailsonalityShareRewardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * share 模块注销级联（Story 7.3，1.1.6 分享奖励补齐）：
 * {@code id_card_share_rewards} / {@code age_card_share_rewards} /
 * {@code tailsonality_share_rewards} / {@code passport_share_rewards}（V1.3.2 Story 4.5）发放留痕
 * + {@code share_reward_quotas} 月度额度行
 * 均为纯个人数据，随 PawCoin 钱包/流水同口径<b>物理删除</b>（D1；奖励对应的币账
 * 已由 {@code PawCoinAccountDeletionService} 作废归零并删流水）。幂等可重跑。
 */
@Service
public class ShareRewardDeletionService {

    private final IdCardShareRewardRepository rewards;
    private final AgeCardShareRewardRepository ageCardRewards;
    private final TailsonalityShareRewardRepository tailsonalityRewards;
    private final PassportShareRewardRepository passportRewards;
    private final ShareRewardQuotaRepository quotas;

    public ShareRewardDeletionService(IdCardShareRewardRepository rewards,
            AgeCardShareRewardRepository ageCardRewards, ShareRewardQuotaRepository quotas,
            TailsonalityShareRewardRepository tailsonalityRewards, PassportShareRewardRepository passportRewards) {
        this.rewards = rewards;
        this.ageCardRewards = ageCardRewards;
        this.tailsonalityRewards = tailsonalityRewards;
        this.passportRewards = passportRewards;
        this.quotas = quotas;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        rewards.deleteByUserId(userId);
        // 🔴 V1.3.0 Story 5.3 新增的渠道账本也必须进来（CLAUDE.md 安全攸关：
        // 新增的带用户外键的表都要进注销级联）。同一个用户的两种分享留痕在注销后
        // 表现必须一致 —— 一种消失、一种留着是最难发现的那类不一致。
        ageCardRewards.deleteByUserId(userId);
        // V1.3.2 Story 4.5：两个新渠道账本同口径物理删除（删档时由外键置空，注销时随用户删）。
        tailsonalityRewards.deleteByUserId(userId);
        passportRewards.deleteByUserId(userId);
        quotas.deleteByUserId(userId);
    }
}
