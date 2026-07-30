package com.tailtopia.config.service;

import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import java.util.List;
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

    public PlatformConfigService(PricingConfigRepository pricing, PawCoinConfigRepository pawcoin,
            PawCoinTopupTierRepository tiers) {
        this.pricing = pricing;
        this.pawcoin = pawcoin;
        this.tiers = tiers;
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
