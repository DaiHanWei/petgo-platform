package com.tailtopia.admin.config.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.config.dto.PawCoinForm;
import com.tailtopia.admin.config.dto.PricingForm;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.ConfigChangeLog.ConfigType;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营配置**写**服务（Story 9.2，AB-8A/8F/6A/6B）。归 admin slice——校验护栏 + 逐字段变更日志
 * （{@code config_change_logs}）+ 审计哈希链（{@link AdminAuditService}）。改值只影响后续（历史落快照）。
 *
 * <p>护栏：premium_rate∈[0,50]、vet_share_rate∈[0,100]、monthly_free_quota∈[0,35]、金额非负；
 * 充值档位**保底 ≥1 启用**（禁停最后一个）。无变更字段不记日志、不记审计。
 */
@Service
public class AdminConfigService {

    private final PricingConfigRepository pricingRepo;
    private final PawCoinConfigRepository pawcoinRepo;
    private final PawCoinTopupTierRepository tierRepo;
    private final ConfigChangeLogRepository changeLogs;
    private final AdminAuditService audit;

    public AdminConfigService(PricingConfigRepository pricingRepo, PawCoinConfigRepository pawcoinRepo,
            PawCoinTopupTierRepository tierRepo, ConfigChangeLogRepository changeLogs,
            AdminAuditService audit) {
        this.pricingRepo = pricingRepo;
        this.pawcoinRepo = pawcoinRepo;
        this.tierRepo = tierRepo;
        this.changeLogs = changeLogs;
        this.audit = audit;
    }

    // ── 定价 ──────────────────────────────────────────────────────────────────
    @Transactional
    public void updatePricing(PricingForm form, long adminId) {
        require(form.vetConsultPrice() >= 0 && form.aiUnlockPrice() >= 0 && form.idHdDownloadPrice() >= 0,
                "价格不可为负");
        require(form.vetShareRate() >= 0 && form.vetShareRate() <= 100, "兽医分成须在 0–100");
        require(form.monthlyFreeQuota() >= 0 && form.monthlyFreeQuota() <= 35, "月免费额度须在 0–35");

        PricingConfig c = pricingRepo.findById(PricingConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("pricing_config 缺失"));
        List<ConfigChangeLog> logs = new ArrayList<>();
        diff(logs, ConfigType.PRICING, "vet_consult_price", c.getVetConsultPrice(), form.vetConsultPrice(), adminId);
        diff(logs, ConfigType.PRICING, "vet_share_rate", c.getVetShareRate(), form.vetShareRate(), adminId);
        diff(logs, ConfigType.PRICING, "ai_unlock_price", c.getAiUnlockPrice(), form.aiUnlockPrice(), adminId);
        diff(logs, ConfigType.PRICING, "id_hd_download_price", c.getIdHdDownloadPrice(), form.idHdDownloadPrice(), adminId);
        diff(logs, ConfigType.PRICING, "monthly_free_quota", c.getMonthlyFreeQuota(), form.monthlyFreeQuota(), adminId);
        if (logs.isEmpty()) {
            return; // 无变更 → 不写、不审计。
        }
        c.setVetConsultPrice(form.vetConsultPrice());
        c.setVetShareRate(form.vetShareRate());
        c.setAiUnlockPrice(form.aiUnlockPrice());
        c.setIdHdDownloadPrice(form.idHdDownloadPrice());
        c.setMonthlyFreeQuota(form.monthlyFreeQuota());
        pricingRepo.save(c);
        commit(logs, adminId, "PRICING", "pricing_config");
    }

    // ── PawCoin ───────────────────────────────────────────────────────────────
    @Transactional
    public void updatePawCoin(PawCoinForm form, long adminId) {
        require(form.premiumRate() >= 0 && form.premiumRate() <= 50, "溢价须在 0–50");

        PawCoinConfig c = pawcoinRepo.findById(PawCoinConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("pawcoin_config 缺失"));
        List<ConfigChangeLog> logs = new ArrayList<>();
        diff(logs, ConfigType.PAWCOIN, "premium_rate", c.getPremiumRate(), form.premiumRate(), adminId);
        diff(logs, ConfigType.PAWCOIN, "topup_paused", c.isTopupPaused(), form.topupPaused(), adminId);
        if (logs.isEmpty()) {
            return;
        }
        c.setPremiumRate(form.premiumRate());
        c.setTopupPaused(form.topupPaused());
        pawcoinRepo.save(c);
        commit(logs, adminId, "PAWCOIN", "pawcoin_config");
    }

    // ── 充值档位启停（保底 ≥1）─────────────────────────────────────────────────
    @Transactional
    public void setTierEnabled(long tierId, boolean enabled, long adminId) {
        PawCoinTopupTier tier = tierRepo.findById(tierId)
                .orElseThrow(() -> AppException.notFound("充值档位不存在"));
        if (tier.isEnabled() == enabled) {
            return; // 无变更。
        }
        if (!enabled && tierRepo.countByEnabledTrue() <= 1) {
            throw AppException.validation("至少保留 1 个启用的充值档位");
        }
        tier.setEnabled(enabled);
        tierRepo.save(tier);
        List<ConfigChangeLog> logs = new ArrayList<>();
        logs.add(ConfigChangeLog.of(ConfigType.TOPUP_TIER, "tier." + tier.getTierKey() + ".enabled",
                String.valueOf(!enabled), String.valueOf(enabled), adminId));
        commit(logs, adminId, "TOPUP_TIER", "tier:" + tier.getTierKey());
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────
    private void diff(List<ConfigChangeLog> logs, ConfigType type, String field, Object oldVal,
            Object newVal, long adminId) {
        String o = String.valueOf(oldVal);
        String n = String.valueOf(newVal);
        if (!o.equals(n)) {
            logs.add(ConfigChangeLog.of(type, field, o, n, adminId));
        }
    }

    private void commit(List<ConfigChangeLog> logs, long adminId, String action, String targetId) {
        changeLogs.saveAll(logs);
        audit.record(adminId, "CONFIG_UPDATE_" + action, "config", targetId,
                logs.size() + " field(s) changed");
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw AppException.validation(msg);
        }
    }
}
