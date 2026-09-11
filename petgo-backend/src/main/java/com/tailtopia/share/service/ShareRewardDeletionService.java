package com.tailtopia.share.service;

import com.tailtopia.share.repository.AgeCardShareRewardRepository;
import com.tailtopia.share.repository.IdCardShareRewardRepository;
import com.tailtopia.share.repository.ShareRewardQuotaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * share 模块注销级联（Story 7.3，1.1.6 分享奖励补齐）：
 * {@code id_card_share_rewards} / {@code age_card_share_rewards} 发放留痕
 * + {@code share_reward_quotas} 月度额度行
 * 均为纯个人数据，随 PawCoin 钱包/流水同口径<b>物理删除</b>（D1；奖励对应的币账
 * 已由 {@code PawCoinAccountDeletionService} 作废归零并删流水）。幂等可重跑。
 */
@Service
public class ShareRewardDeletionService {

    private final IdCardShareRewardRepository rewards;
    private final AgeCardShareRewardRepository ageCardRewards;
    private final ShareRewardQuotaRepository quotas;

    public ShareRewardDeletionService(IdCardShareRewardRepository rewards,
            AgeCardShareRewardRepository ageCardRewards, ShareRewardQuotaRepository quotas) {
        this.rewards = rewards;
        this.ageCardRewards = ageCardRewards;
        this.quotas = quotas;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        rewards.deleteByUserId(userId);
        // 🔴 V1.3.0 Story 5.3 新增的渠道账本也必须进来（CLAUDE.md 安全攸关：
        // 新增的带用户外键的表都要进注销级联）。同一个用户的两种分享留痕在注销后
        // 表现必须一致 —— 一种消失、一种留着是最难发现的那类不一致。
        ageCardRewards.deleteByUserId(userId);
        quotas.deleteByUserId(userId);
    }
}
