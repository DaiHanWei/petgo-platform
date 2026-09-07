package com.tailtopia.admin.settlement.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.settlement.service.AdminSettlementService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
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
 * 后台兽医分成月结对账（Story 9.5，AB-8D）。Thymeleaf admin slice，{@code /admin/settlements/**}，redirect+flash。
 * 门控：对账查看 {@code settlement.view} / 确认打款·归档 {@code settlement.payout}（SUPER_ADMIN 隐式全权）。
 */
@Controller
public class AdminSettlementController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('settlement.view')";
    private static final String PAYOUT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('settlement.payout')";

    private final AdminSettlementService service;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminSettlementController(AdminSettlementService service,
            Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @GetMapping("/admin/settlements")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "settlements");
        model.addAttribute("settlements", service.list());
        return "admin/settlements";
    }

    @PostMapping("/admin/settlements/{id}/pay")
    @PreAuthorize(PAYOUT_AUTH)
    public String pay(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(required = false) String proof, RedirectAttributes flash) {
        try {
            service.markPaid(id, proof, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.settlement.paid"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/settlements";
    }

    @PostMapping("/admin/settlements/{id}/archive")
    @PreAuthorize(PAYOUT_AUTH)
    public String archive(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            service.archive(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.settlement.archived"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/settlements";
    }
}
