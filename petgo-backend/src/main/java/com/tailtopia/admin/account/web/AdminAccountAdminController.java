package com.tailtopia.admin.account.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.dto.CreateAdminAccountForm;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
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
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('admin.view_accounts') or hasAuthority('admin.create_account')";
    private static final String DEACTIVATE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('admin.deactivate')";

    private final AdminAccountService accountService;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminAccountAdminController(AdminAccountService accountService,
            Messages msg) {
        this.accountService = accountService;
        this.msg = msg;
    }

    @GetMapping("/admin/accounts")
    @PreAuthorize(VIEW_AUTH)
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
                    form.getRole(), form.getPermissionCodes(), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.created", id, form.getLarkEmail()));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
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
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.permsUpdated", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/accounts";
    }

    /**
     * 改岗位角色（V165）。门控与创建账号同级（{@code admin.create_account}）——改角色就是重新授权，
     * 与建号是同一量级的动作，不该比它更容易拿到。
     */
    @PostMapping("/admin/accounts/{id}/role")
    @PreAuthorize(CREATE_AUTH)
    public String changeRole(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam("role") AdminRole role,
            RedirectAttributes flash) {
        try {
            accountService.changeRole(id, role, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.roleChanged", id, msg.get(role.titleCode())));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/accounts/{id}/deactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            accountService.deactivate(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.deactivated", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/admin/accounts/{id}/reactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String reactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            accountService.reactivate(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.reactivated", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/accounts";
    }

    private void populate(Model model) {
        model.addAttribute("active", "accounts");
        model.addAttribute("accounts", accountService.list());
        model.addAttribute("allPermissions", AdminPermissions.ALL);
        model.addAttribute("permissionGroups", AdminPermissions.GROUPS);
        model.addAttribute("roles", AdminRole.selectable());
        // 角色 → 权限码，供页面在选角色时即时预览「这个岗位能看到什么」（仅体验；真正的授权在服务端按角色解析）。
        model.addAttribute("rolePermissions", java.util.Arrays.stream(AdminRole.values())
                .collect(java.util.stream.Collectors.toMap(Enum::name, AdminRole::permissionCodes,
                        (a, b) -> a, java.util.LinkedHashMap::new)));
    }
}
