package com.tailtopia.admin.config.web;

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
            flash.addFlashAttribute("notice", "定价已更新（仅影响后续新成交；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/config";
    }

    @PostMapping("/admin/config/pawcoin")
    @PreAuthorize(EDIT_AUTH)
    public String updatePawCoin(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam int premiumRate,
            @RequestParam(defaultValue = "false") boolean topupPaused, RedirectAttributes flash) {
        try {
            write.updatePawCoin(new PawCoinForm(premiumRate, topupPaused), admin.getAdminAccountId());
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
