package com.tailtopia.config.service;

import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.domain.SupportContactConfig;
import com.tailtopia.config.repository.FeedRankConfigRepository;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.config.repository.SupportContactConfigRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台运营配置**只读**服务（Story 9.2）。中立模块——供计费/额度/充值等资金模块读取当前配置值
 * （consult 计费 / AI 解锁 / HD 下载 / 免费额度 / 充值档位·暂停）。
 *
 * <p>写入（校验 + 变更日志 + 审计哈希链）在 admin slice 的 {@code AdminConfigService}，与本读服务分离，
 * 避免资金模块依赖 admin。<b>无缓存</b>（护栏禁 Caffeine/MQ）：单行/小表直读，≤500 DAU 足够。
 * 单行 {@code id=1} 由 V78 种子保证存在，缺失即配置基建异常（fail-fast）。
 */
@Service
public class PlatformConfigService {

    private final PricingConfigRepository pricing;
    private final PawCoinConfigRepository pawcoin;
    private final PawCoinTopupTierRepository tiers;
    private final FeedRankConfigRepository feedRank;
    private final SupportContactConfigRepository supportContact;

    public PlatformConfigService(PricingConfigRepository pricing, PawCoinConfigRepository pawcoin,
            PawCoinTopupTierRepository tiers, FeedRankConfigRepository feedRank,
            SupportContactConfigRepository supportContact) {
        this.pricing = pricing;
        this.pawcoin = pawcoin;
        this.tiers = tiers;
        this.feedRank = feedRank;
        this.supportContact = supportContact;
    }

    /**
     * 当前客服联系方式（单行，Story 3-1）。
     *
     * <p>🔴 <b>刻意返回 {@link Optional} 而不是像上面几个那样缺行即抛</b>：
     * 客服号的消费方里有**登录被拒的文案**与**兽医登录页的客服弹窗** ——
     * 配置表出问题时抛异常，等于让「账号被停用」这条提示和登录页一起挂掉，
     * 用户连找谁申诉都看不到。缺行由 {@code DbSupportContactProvider} 回退到内置默认值。
     */
    @Transactional(readOnly = true)
    public Optional<SupportContactConfig> supportContact() {
        return supportContact.findById(SupportContactConfig.SINGLETON_ID);
    }

    /**
     * 当前推荐算法参数（单行，V1.1.6 Story 16.4）。
     *
     * <p>🛡 <b>无缓存</b>（沿用本类既有口径，护栏禁通用缓存层）：单行直读，
     * 而且推荐序里它<b>每次生成序列只读一次</b>（不是每条内容读一次）。
     */
    @Transactional(readOnly = true)
    public FeedRankConfig feedRank() {
        return feedRank.findById(FeedRankConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("feed_rank_config 单行缺失（16.4 种子）"));
    }

    /** 当前定价配置（单行）。 */
    @Transactional(readOnly = true)
    public PricingConfig pricing() {
        return pricing.findById(PricingConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("pricing_config 单行缺失（V78 种子）"));
    }

    /** 当前 PawCoin 配置（单行）。 */
    @Transactional(readOnly = true)
    public PawCoinConfig pawcoin() {
        return pawcoin.findById(PawCoinConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("pawcoin_config 单行缺失（V78 种子）"));
    }

    /** 启用中的充值档位（按 sortOrder 升序）。 */
    @Transactional(readOnly = true)
    public List<PawCoinTopupTier> enabledTiers() {
        return tiers.findByEnabledTrueOrderBySortOrderAsc();
    }

    /** 全部充值档位（含停用，按 sortOrder）。 */
    @Transactional(readOnly = true)
    public List<PawCoinTopupTier> allTiers() {
        return tiers.findAllByOrderBySortOrderAsc();
    }
}
