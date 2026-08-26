package com.tailtopia.admin.config.web;

import com.tailtopia.admin.config.dto.FeedRankForm;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.config.dto.PawCoinForm;
import com.tailtopia.admin.config.dto.PricingForm;
import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台运营配置（Story 9.2，AB-8A/8F/6A/6B）。Thymeleaf admin slice，{@code /admin/config/**}，redirect+flash。
 * 门控：查看 {@code config.view} / 修改 {@code config.edit}（SUPER_ADMIN 隐式全权）。校验/变更日志/审计在
 * {@link AdminConfigService}。改值只影响后续（历史落快照）。
 */
@Controller
public class AdminConfigController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.view')";
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.edit')";

    /**
     * 分享奖励的两个独立权限（Story 18.3 · AC5）。
     *
     * <p>🛡 从 {@link AdminPermissions} 常量拼出来，模板里的 {@code sec:authorize} 用的是
     * 同一个码 —— 「侧栏门与控制器注解逐字一致」那条要求就不靠人去对照两段字符串。
     */
    static final String SHARE_REWARD_VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONFIG_SHARE_REWARD_VIEW + "')";
    static final String SHARE_REWARD_EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONFIG_SHARE_REWARD_EDIT + "')";

    private final PlatformConfigService read;
    private final com.tailtopia.share.repository.ShareRewardQuotaStatsRepository shareStats;
    private final AdminConfigService write;

    public AdminConfigController(PlatformConfigService read, AdminConfigService write,
            com.tailtopia.share.repository.ShareRewardQuotaStatsRepository shareStats) {
        this.read = read;
        this.shareStats = shareStats;
        this.write = write;
    }

    @GetMapping("/admin/config")
    @PreAuthorize(VIEW_AUTH)
    public String view(Model model) {
        model.addAttribute("active", "config");
        model.addAttribute("pricing", read.pricing());
        model.addAttribute("pawcoin", read.pawcoin());
        model.addAttribute("tiers", read.allTiers());
        // V1.1.6 Story 16.4：推荐算法参数（挂既有配置页，🛡 不新建后台模块）
        // Story 18.3 · AC2/AC3：白嫖倍数与当月消耗必须**同屏**——
        // 「月度上限 30」和「HD 解锁 60」分开看都合理，放一起才看得出「两个月白嫖一次」。
        model.addAttribute("shareRewardOverview", shareRewardOverview());
        return "admin/config";
    }

    /**
     * 分享奖励四项（Story 18.3 · AB-3M）。
     *
     * <p>🛡 独立端点 + 独立权限（AC5）：总开关的意义是「发现被刷要能立刻关掉」，
     * 塞进 {@code config.edit} 就意味着想关它的人必须同时握有改兽医定价与分成的权限。
     * ⚠️ checkbox 未勾选时浏览器**不提交该参数** ⇒ {@code shareRewardEnabled}
     * 必须给 {@code defaultValue="false"}，否则「取消勾选」会 400 而不是关掉开关。
     */
    @PostMapping("/admin/config/share-reward")
    @PreAuthorize(SHARE_REWARD_EDIT_AUTH)
    public String updateShareReward(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(defaultValue = "false") boolean shareRewardEnabled,
            @RequestParam(defaultValue = "0") long shareRewardMonthlyCap,
            @RequestParam(defaultValue = "0") long idCardShareReward,
            @RequestParam(defaultValue = "0") int idCardShareDailyCap,
            RedirectAttributes flash) {
        try {
            write.updateShareReward(new com.tailtopia.admin.config.dto.ShareRewardForm(
                    shareRewardEnabled, shareRewardMonthlyCap, idCardShareReward,
                    idCardShareDailyCap), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "分享奖励配置已更新（操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/config";
    }

    /** 分享奖励的同屏对照数据（HD 价 / 白嫖倍数 / 当月消耗）。 */
    private com.tailtopia.admin.config.dto.ShareRewardOverview shareRewardOverview() {
        long hdPrice = read.pricing().getIdHdDownloadPrice();
        long cap = read.pawcoin().getShareRewardMonthlyCap();
        String period = com.tailtopia.share.service.ShareRewardService
                .periodOf(java.time.Instant.now());
        return new com.tailtopia.admin.config.dto.ShareRewardOverview(
                hdPrice, cap,
                com.tailtopia.admin.config.dto.ShareRewardOverview
                        .monthsPerHdUnlock(hdPrice, cap),
                shareStats.sumGranted(period),
                cap > 0 ? shareStats.countAtCap(period, cap) : 0,
                period);
    }

    @PostMapping("/admin/config/pricing")
    @PreAuthorize(EDIT_AUTH)
    public String updatePricing(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long vetConsultPrice, @RequestParam int vetShareRate,
            @RequestParam long aiUnlockPrice, @RequestParam long idHdDownloadPrice,
            @RequestParam int monthlyFreeQuota, RedirectAttributes flash) {
        try {
            write.updatePricing(new PricingForm(vetConsultPrice, vetShareRate, aiUnlockPrice,
                    idHdDownloadPrice, monthlyFreeQuota), admin.getAdminAccountId());
            // bug 20260721-346：定价保存成功提示改用 toast（短暂自动消失），区别于常驻 notice 横幅。
            flash.addFlashAttribute("toast", "定价已更新（仅影响后续新成交；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/config";
    }

    @PostMapping("/admin/config/pawcoin")
    @PreAuthorize(EDIT_AUTH)
    public String updatePawCoin(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam int premiumRate,
            @RequestParam(defaultValue = "0") long premiumFixed,
            @RequestParam(defaultValue = "false") boolean topupPaused, RedirectAttributes flash) {
        try {
            write.updatePawCoin(new PawCoinForm(premiumRate, premiumFixed, topupPaused),
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "PawCoin 配置已更新（操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/config";
    }

    @PostMapping("/admin/config/tiers/{id}/enabled")
    @PreAuthorize(EDIT_AUTH)
    public String setTierEnabled(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam boolean enabled, RedirectAttributes flash) {
        try {
            write.setTierEnabled(id, enabled, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "充值档位已更新（操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/config";
    }
}
