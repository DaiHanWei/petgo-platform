package com.tailtopia.admin.virtual.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
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
 * 后台虚拟账号管理（Story 9.8，A-6）。Thymeleaf admin slice，{@code /admin/virtual-accounts/**}，redirect+flash。
 * 门控 {@code virtual_account.manage}（SUPER_ADMIN 隐式全权）。
 */
@Controller
public class AdminVirtualAccountController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.view') or hasAuthority('virtual_account.manage')";

    private final AdminVirtualAccountService service;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminVirtualAccountController(AdminVirtualAccountService service,
            Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @GetMapping("/admin/virtual-accounts")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "virtual-accounts");
        model.addAttribute("accounts", service.list());
        return "admin/virtual-accounts";
    }

    @PostMapping("/admin/virtual-accounts")
    @PreAuthorize(AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String nickname, @RequestParam(required = false) String avatarUrl,
            RedirectAttributes flash) {
        try {
            long id = service.create(nickname, avatarUrl, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.virtualAccount.created", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/virtual-accounts";
    }

    @PostMapping("/admin/virtual-accounts/{id}/enabled")
    @PreAuthorize(AUTH)
    public String setEnabled(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam boolean enabled, RedirectAttributes flash) {
        try {
            service.setEnabled(id, enabled, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.virtualAccount.statusUpdated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/virtual-accounts";
    }
}
