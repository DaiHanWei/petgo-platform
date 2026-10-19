package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.GrowthSpendTier;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.domain.SpendTier;
import com.tailtopia.growth.repository.GrowthSpendTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付用途 → 消费返档位 / 规则（V1.4.0 ① Story 1.3 · 架构 AD-8）。读 {@code growth_spend_tiers}（每次读库，不缓存）；
 * <b>未登记的用途按 {@link SpendTier#DIGITAL}</b>（PRD「新上线的付费功能默认归入数字服务档」）。
 */
@Service
public class GrowthSpendTierResolver {

    private final GrowthSpendTierRepository tiers;

    public GrowthSpendTierResolver(GrowthSpendTierRepository tiers) {
        this.tiers = tiers;
    }

    @Transactional(readOnly = true)
    public SpendTier tierOf(String purpose) {
        if (purpose == null) {
            return SpendTier.DIGITAL;
        }
        return tiers.findById(purpose).map(GrowthSpendTier::getTier).orElse(SpendTier.DIGITAL);
    }

    /** 该用途入账用的规则；不入账（NONE / GIFT）返回 null。 */
    @Transactional(readOnly = true)
    public RuleCode ruleFor(String purpose) {
        return tierOf(purpose).rule();
    }
}
