package com.tailtopia.admin.config.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.config.dto.FeedRankForm;
import com.tailtopia.admin.config.dto.PawCoinForm;
import com.tailtopia.admin.config.dto.PricingForm;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.ConfigChangeLog.ConfigType;
import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.FeedRankConfigRepository;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.content.rank.AttributeTemplate;
import com.tailtopia.admin.config.dto.ShareRewardForm;
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
    private final FeedRankConfigRepository feedRankRepo;

    public AdminConfigService(PricingConfigRepository pricingRepo, PawCoinConfigRepository pawcoinRepo,
            PawCoinTopupTierRepository tierRepo, ConfigChangeLogRepository changeLogs,
            AdminAuditService audit, FeedRankConfigRepository feedRankRepo) {
        this.feedRankRepo = feedRankRepo;
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
        require(form.premiumRate() >= 0 && form.premiumRate() <= 50, "溢价百分比须在 0–50");
        require(form.premiumFixed() >= 0, "固定溢价须 ≥ 0");

        PawCoinConfig c = pawcoinRepo.findById(PawCoinConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("pawcoin_config 缺失"));
        List<ConfigChangeLog> logs = new ArrayList<>();
        diff(logs, ConfigType.PAWCOIN, "premium_rate", c.getPremiumRate(), form.premiumRate(), adminId);
        diff(logs, ConfigType.PAWCOIN, "premium_fixed", c.getPremiumFixed(), form.premiumFixed(), adminId);
        diff(logs, ConfigType.PAWCOIN, "topup_paused", c.isTopupPaused(), form.topupPaused(), adminId);
        if (logs.isEmpty()) {
            return;
        }
        c.setPremiumRate(form.premiumRate());
        c.setPremiumFixed(form.premiumFixed());
        c.setTopupPaused(form.topupPaused());
        pawcoinRepo.save(c);
        commit(logs, adminId, "PAWCOIN", "pawcoin_config");
    }

    // ── 分享奖励四项（V1.1.6 Story 18.3 · AB-3M）─────────────────────────────
    /**
     * 更新分享奖励配置。
     *
     * <p>🛡 AC4：四项均须非负；<b>0 是合法取值</b>（= 不发），不是错误。
     * ⚠️ 「非整数」在参数绑定阶段就被挡住了（{@code long}/{@code int} 参数收到 "1.5" 直接 400），
     * 所以这里只管语义上的非负。
     *
     * <p>🛡 与 {@link #updatePawCoin} 写同一张单行、用同一个 PAWCOIN 审计组（AC1），
     * 但入口与权限分开（AC5）—— 理由见 {@link ShareRewardForm} 的类注释。
     */
    @Transactional
    public void updateShareReward(ShareRewardForm form, long adminId) {
        require(form.shareRewardMonthlyCap() >= 0, "分享奖励月度上限须 ≥ 0（0 = 不发）");
        require(form.idCardShareReward() >= 0, "身份证分享每次发放枚数须 ≥ 0（0 = 不发）");
        require(form.idCardShareDailyCap() >= 0, "身份证分享每日次数上限须 ≥ 0（0 = 不发）");

        PawCoinConfig c = pawcoinRepo.findById(PawCoinConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("pawcoin_config 缺失"));
        List<ConfigChangeLog> logs = new ArrayList<>();
        ConfigType t = ConfigType.PAWCOIN;
        diff(logs, t, "share_reward_enabled", c.isShareRewardEnabled(),
                form.shareRewardEnabled(), adminId);
        diff(logs, t, "share_reward_monthly_cap", c.getShareRewardMonthlyCap(),
                form.shareRewardMonthlyCap(), adminId);
        diff(logs, t, "id_card_share_reward", c.getIdCardShareReward(),
                form.idCardShareReward(), adminId);
        diff(logs, t, "id_card_share_daily_cap", c.getIdCardShareDailyCap(),
                form.idCardShareDailyCap(), adminId);
        if (logs.isEmpty()) {
            return; // 无变更 → 不写、不记日志、不审计（沿用本类既有口径）
        }
        c.setShareRewardEnabled(form.shareRewardEnabled());
        c.setShareRewardMonthlyCap(form.shareRewardMonthlyCap());
        c.setIdCardShareReward(form.idCardShareReward());
        c.setIdCardShareDailyCap(form.idCardShareDailyCap());
        pawcoinRepo.save(c);
        commit(logs, adminId, "PAWCOIN", "pawcoin_config");
    }

    // ── 推荐算法参数（V1.1.6 Story 16.4，FR-95）──────────────────────────────
    /**
     * 更新打分参数。
     *
     * <p>🛡 <b>两项配比的自洽是硬校验</b>（AC4）：不校验的后果是运营把 5/3/2 改成 5/3/3，
     * 窗口凑不满或溢出 —— 而那<b>不会报错</b>，只会让首页节奏莫名其妙，且极难被想到去查配置。
     *
     * <p>🔴 <b>属性配比还要多一道</b>：单项不得超过窗口的一半，否则必然出现同属性相邻，
     * 「穿插」这件事本身失去意义。校验与生成排期用的是<b>同一处</b>规则
     * （{@link AttributeTemplate#rejectUnusableQuotas}）—— 两处各写一遍就会出现
     * 「保存通过了但生成出来的模板是坏的」。
     *
     * <p>⚠️ <b>不含 P95</b>：那是定期重算的动态值（{@code FeedRankP95Scanner}），不是手填常数。
     */
    @Transactional
    public void updateFeedRank(FeedRankForm form, long adminId) {
        require(form.freshnessWeight() >= 0 && form.interactionWeight() >= 0, "权重不可为负");
        require(form.freshnessWeight() + form.interactionWeight() > 0,
                "新鲜度与互动度权重不可同时为 0（那会让所有内容同分）");
        require(form.commentWeight() >= 0, "评论权重不可为负");
        require(form.exposureDecay() >= 0 && form.exposureDecay() <= 1,
                "曝光衰减须在 0–1（大于 1 就是「看过的排更前面」，与这一维的意图相反）");
        // 两端都可取：0 = 关闭抖动（回到纯分数排序），1 = 最强抖动 —— 与建表 CHECK 同一口径。
        require(form.shuffleStrength() >= 0 && form.shuffleStrength() <= 1,
                "刷新抖动幅度须在 0–1（0=关闭抖动，越大下拉刷新换得越狠）");
        require(form.seenWindowDays() >= 1, "曝光窗口须 ≥ 1 天");
        // 🛡 Story 17.1 · AC2：两头都不能取到 —— 与建表 CHECK 同一口径。
        // ≥ 1 不是降权（等于没处置）；= 0 让分数恒为 0 ⇒ 永远排不进推荐序 ⇒ 事实上等于下架，
        // 而那条 AC 明令「限流是降权不是下架」。这里给的是人话理由，
        // 靠 CHECK 兜底会以 500 的形式露出来，运营看不懂。
        require(form.throttleFactor() > 0 && form.throttleFactor() < 1,
                "限流系数须在 0–1 之间且两端都不取："
                        + "取 1 等于没处置，取 0 会让被限流的内容永远排不进首页（那是下架，不是降权）");

        String attrProblem = AttributeTemplate.rejectUnusableQuotas(form.attrFunQuota(),
                form.attrEduQuota(), form.attrLifeQuota(), form.windowSize());
        require(attrProblem == null, attrProblem == null ? "" : attrProblem);

        int speciesSum = form.speciesMainQuota() + form.speciesOtherQuota()
                + form.speciesGeneralQuota();
        require(form.speciesMainQuota() >= 0 && form.speciesOtherQuota() >= 0
                && form.speciesGeneralQuota() >= 0, "物种配比不可为负");
        require(speciesSum == form.windowSize(),
                "物种配比之和（" + speciesSum + "）须等于窗口大小（" + form.windowSize() + "）");

        FeedRankConfig c = feedRankRepo.findById(FeedRankConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("feed_rank_config 缺失"));
        List<ConfigChangeLog> logs = new ArrayList<>();
        ConfigType t = ConfigType.FEED_RANK;
        diff(logs, t, "freshness_weight", c.getFreshnessWeight(), form.freshnessWeight(), adminId);
        diff(logs, t, "interaction_weight", c.getInteractionWeight(), form.interactionWeight(), adminId);
        diff(logs, t, "comment_weight", c.getCommentWeight(), form.commentWeight(), adminId);
        diff(logs, t, "exposure_decay", c.getExposureDecay(), form.exposureDecay(), adminId);
        diff(logs, t, "shuffle_strength", c.getShuffleStrength(), form.shuffleStrength(), adminId);
        diff(logs, t, "throttle_factor", c.getThrottleFactor(), form.throttleFactor(), adminId);
        diff(logs, t, "seen_window_days", c.getSeenWindowDays(), form.seenWindowDays(), adminId);
        diff(logs, t, "window_size", c.getWindowSize(), form.windowSize(), adminId);
        diff(logs, t, "attr_fun_quota", c.getAttrFunQuota(), form.attrFunQuota(), adminId);
        diff(logs, t, "attr_edu_quota", c.getAttrEduQuota(), form.attrEduQuota(), adminId);
        diff(logs, t, "attr_life_quota", c.getAttrLifeQuota(), form.attrLifeQuota(), adminId);
        diff(logs, t, "species_main_quota", c.getSpeciesMainQuota(), form.speciesMainQuota(), adminId);
        diff(logs, t, "species_other_quota", c.getSpeciesOtherQuota(), form.speciesOtherQuota(), adminId);
        diff(logs, t, "species_general_quota", c.getSpeciesGeneralQuota(), form.speciesGeneralQuota(), adminId);
        if (logs.isEmpty()) {
            return; // 无变更 → 不写、不审计（沿用本类既有口径）。
        }
        c.setFreshnessWeight(form.freshnessWeight());
        c.setInteractionWeight(form.interactionWeight());
        c.setCommentWeight(form.commentWeight());
        c.setExposureDecay(form.exposureDecay());
        c.setShuffleStrength(form.shuffleStrength());
        c.setThrottleFactor(form.throttleFactor());
        c.setSeenWindowDays(form.seenWindowDays());
        c.setWindowSize(form.windowSize());
        c.setAttrFunQuota(form.attrFunQuota());
        c.setAttrEduQuota(form.attrEduQuota());
        c.setAttrLifeQuota(form.attrLifeQuota());
        c.setSpeciesMainQuota(form.speciesMainQuota());
        c.setSpeciesOtherQuota(form.speciesOtherQuota());
        c.setSpeciesGeneralQuota(form.speciesGeneralQuota());
        feedRankRepo.save(c);
        commit(logs, adminId, "FEED_RANK", "feed_rank_config");
    }

    // ── 充值档位启停（保底 ≥1）─────────────────────────────────────────────────
    @Transactional
    public void setTierEnabled(long tierId, boolean enabled, long adminId) {
        PawCoinTopupTier tier = tierRepo.findById(tierId)
                .orElseThrow(() -> AppException.notFound("充值档位不存在").code("admin.err.config.tierNotFound"));
        if (tier.isEnabled() == enabled) {
            return; // 无变更。
        }
        if (!enabled && tierRepo.countByEnabledTrue() <= 1) {
            throw AppException.validation("至少保留 1 个启用的充值档位").code("admin.err.config.keepOneTier");
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
