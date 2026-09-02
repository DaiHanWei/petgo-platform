package com.tailtopia.share.service;

import com.tailtopia.share.repository.IdCardShareRewardRepository;
import com.tailtopia.share.repository.ShareRewardQuotaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * share 模块注销级联（Story 7.3，1.1.6 分享奖励补齐）：
 * {@code id_card_share_rewards} 发放留痕 + {@code share_reward_quotas} 月度额度行
 * 均为纯个人数据，随 PawCoin 钱包/流水同口径<b>物理删除</b>（D1；奖励对应的币账
 * 已由 {@code PawCoinAccountDeletionService} 作废归零并删流水）。幂等可重跑。
 */
@Service
public class ShareRewardDeletionService {

    private final IdCardShareRewardRepository rewards;
    private final ShareRewardQuotaRepository quotas;

    public ShareRewardDeletionService(IdCardShareRewardRepository rewards,
            ShareRewardQuotaRepository quotas) {
        this.rewards = rewards;
        this.quotas = quotas;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        rewards.deleteByUserId(userId);
        quotas.deleteByUserId(userId);
    }
}
