package com.tailtopia.admin.account.web;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.dto.CreateAdminAccountForm;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台账号管理页（Story 1.5，AC3/AC5/AC6/AC7）。SSR + redirect-PRG（与既有 vets/reports 页一致）。
 *
 * <p>方法级门控（A5，SUPER_ADMIN 经表达式隐式通过）：查看/创建/改权限 → {@code admin.create_account}；
 * 停用/激活 → {@code admin.deactivate}。普通账号直接请求未授权端点 → 403（前端隐藏入口仅体验、非安全边界）。
 */
@Controller
public class AdminAccountAdminController {

    private static final String CREATE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('admin.create_account')";
    private static final String DEACTIVATE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('admin.deactivate')";

    private final AdminAccountService accountService;

    public AdminAccountAdminController(AdminAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/admin/accounts")
    @PreAuthorize(CREATE_AUTH)
    public String accounts(Model model) {
        populate(model);
        if (!model.containsAttribute("createAdminAccountForm")) {
            model.addAttribute("createAdminAccountForm", new CreateAdminAccountForm());
        }
        return "admin/admin-accounts";
    }

    @PostMapping("/admin/accounts")
    @PreAuthorize(CREATE_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @Valid @ModelAttribute("createAdminAccountForm") CreateAdminAccountForm form,
            BindingResult binding, Model model, RedirectAttributes flash) {
        if (binding.hasErrors()) {
            populate(model);
            return "admin/admin-accounts";
        }
        try {
            long id = accountService.createAccount(form.getLarkEmail(), form.getDisplayName(),
                    form.getAccountType(), form.getPermissionCodes(), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已创建后台账号 #" + id + "（" + form.getLarkEmail() + "）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/accounts/{id}/permissions")
    @PreAuthorize(CREATE_AUTH)
    public String updatePermissions(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id,
            @RequestParam(value = "permissionCodes", required = false) List<String> permissionCodes,
            RedirectAttributes flash) {
        try {
            accountService.updatePermissions(id, permissionCodes, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已更新账号 #" + id + " 的权限（下次登录生效）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/accounts/{id}/deactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            accountService.deactivate(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已停用账号 #" + id + "（其会话即时失效）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/accounts/{id}/reactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String reactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            accountService.reactivate(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已重新激活账号 #" + id);
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/accounts";
    }

    private void populate(Model model) {
        model.addAttribute("active", "accounts");
        model.addAttribute("accounts", accountService.list());
        model.addAttribute("allPermissions", AdminPermissions.ALL);
        model.addAttribute("permissionGroups", AdminPermissions.GROUPS);
        model.addAttribute("accountTypes", AdminAccountType.values());
    }
}
