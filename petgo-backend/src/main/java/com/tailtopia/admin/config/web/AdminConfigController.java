package com.tailtopia.admin.config.web;

import com.tailtopia.admin.config.dto.FeedRankForm;
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

    private final PlatformConfigService read;
    private final AdminConfigService write;

    public AdminConfigController(PlatformConfigService read, AdminConfigService write) {
        this.read = read;
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
        model.addAttribute("feedRank", read.feedRank());
        return "admin/config";
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
            write.updatePawCoin(new PawCoinForm(premiumRate, premiumFixed, topupPaused), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "PawCoin 配置已更新（操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/config";
    }

    /**
     * 推荐算法参数（V1.1.6 Story 16.4，FR-95）。
     *
     * <p>🛡 复用本页既有的 {@code config.view / config.edit} 权限码 —— <b>不新增码</b>：
     * 权限码一旦落地即冻结，为一个区块新增两个码会让权限表越长越没人看得懂，
     * 而它本来就属于"运营配置"这件事。
     */
    @PostMapping("/admin/config/feed-rank")
    @PreAuthorize(EDIT_AUTH)
    public String updateFeedRank(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam double freshnessWeight, @RequestParam double interactionWeight,
            @RequestParam double commentWeight, @RequestParam double exposureDecay,
            @RequestParam double throttleFactor, @RequestParam int seenWindowDays, @RequestParam int windowSize,
            @RequestParam int attrFunQuota, @RequestParam int attrEduQuota,
            @RequestParam int attrLifeQuota, @RequestParam int speciesMainQuota,
            @RequestParam int speciesOtherQuota, @RequestParam int speciesGeneralQuota,
            RedirectAttributes flash) {
        write.updateFeedRank(new FeedRankForm(freshnessWeight, interactionWeight, commentWeight,
                exposureDecay, throttleFactor, seenWindowDays, windowSize, attrFunQuota, attrEduQuota,
                attrLifeQuota, speciesMainQuota, speciesOtherQuota, speciesGeneralQuota),
                admin.getAdminAccountId());
        flash.addFlashAttribute("ok", "推荐算法参数已更新");
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
