package com.tailtopia.admin.account.web;

import com.tailtopia.admin.account.dto.CreateAdminAccountForm;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.roles.dto.RoleOption;
import com.tailtopia.admin.roles.dto.RoleSelection;
import com.tailtopia.admin.roles.service.AdminRoleService;
import com.tailtopia.admin.roles.service.RolePermissionResolver;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
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
    /** 换绑邮箱 = 身份移交，仅超管（V1.3.0 Story 1.3）。 */
    private static final String REBIND_AUTH = "hasRole('SUPER_ADMIN')";

    private final AdminAccountService accountService;
    /** 角色下拉 / 选项解析 / 权限矩阵（V1.3.0 Story 1.6 接入角色表）。 */
    private final AdminRoleService roleService;
    private final RolePermissionResolver resolver;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminAccountAdminController(AdminAccountService accountService, AdminRoleService roleService,
            RolePermissionResolver resolver, Messages msg) {
        this.accountService = accountService;
        this.roleService = roleService;
        this.resolver = resolver;
        this.msg = msg;
    }

    @GetMapping("/admin/accounts")
    @PreAuthorize(VIEW_AUTH)
    public String accounts(@AuthenticationPrincipal AdminUserDetails admin, Model model) {
        populate(model);
        // V1.3.0 Story 1.2：自己那一行的「停用 / 改角色」渲染禁用态（体验；安全边界在服务层 self 护栏）。
        model.addAttribute("selfId", admin == null ? null : admin.getAdminAccountId());
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
            RoleSelection sel = roleService.resolveSelection(form.getRoleValue());
            long id = accountService.createAccount(form.getLarkEmail(), form.getDisplayName(),
                    sel, form.getPermissionCodes(), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.created", id, form.getLarkEmail()));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        } catch (DataIntegrityViolationException e) {
            // 并发建号 / 换绑撞部分唯一索引（服务层查重是 read-then-write，索引是兜底）：按邮箱重复提示，不 500。
            flash.addFlashAttribute("error", msg.get("admin.err.account.emailExists", form.getLarkEmail()));
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
            @PathVariable long id, @RequestParam("role") String roleValue,
            RedirectAttributes flash) {
        try {
            // Story 1.6：选项值 enum:<NAME> / tpl:<id> → role + role_id 成对（预置四岗补表 id，自定义 → ROLE_TEMPLATE）。
            RoleSelection sel = roleService.resolveSelection(roleValue);
            accountService.changeRole(id, sel, admin.getAdminAccountId());
            String label = sel.role() == com.tailtopia.admin.account.domain.AdminRole.ROLE_TEMPLATE
                    ? sel.label() : msg.get(sel.role().titleCode());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.roleChanged", id, label));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/accounts";
    }

    /** 改显示名（V1.3.0 Story 1.2）。与建号 / 改权限 / 改角色同门槛。 */
    @PostMapping("/admin/accounts/{id}/rename")
    @PreAuthorize(CREATE_AUTH)
    public String rename(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("displayName") String displayName, RedirectAttributes flash) {
        try {
            accountService.rename(id, displayName, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.renamed", id,
                    displayName == null ? "" : displayName.trim()));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/accounts";
    }

    /** 换绑 Lark 邮箱（V1.3.0 Story 1.3）。仅超管；成功后旧邮箱会话由 AdminSessionGuardFilter 踢重登。 */
    @PostMapping("/admin/accounts/{id}/rebind-email")
    @PreAuthorize(REBIND_AUTH)
    public String rebindEmail(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("newEmail") String newEmail, RedirectAttributes flash) {
        try {
            accountService.rebindEmail(id, newEmail, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.emailRebound", id,
                    newEmail == null ? "" : newEmail.trim()));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        } catch (DataIntegrityViolationException e) {
            // 并发换绑撞 uq_admin_accounts_lark_email_active（提交时才抛，服务层 exists 查重拦不住）：按邮箱重复提示。
            flash.addFlashAttribute("error", msg.get("admin.err.account.emailExists",
                    newEmail == null ? "" : newEmail.trim()));
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
        var accounts = accountService.list();
        model.addAttribute("accounts", accounts);
        // V1.3.0 Story 1.3：bootstrap 超管行「换绑邮箱」禁用态（判定逻辑与服务层护栏同源）。
        model.addAttribute("bootstrapEmails", accounts.stream()
                .map(v -> v.larkEmail()).filter(accountService::isBootstrapEmail).toList());
        // Story 1.6：角色下拉 = 枚举（超管 / 运营主管）+ admin_roles 全部行 + CUSTOM；权限面板按 AdminPageCatalog 8 组。
        List<RoleOption> roleOptions = roleService.options(code -> msg.get(code));
        model.addAttribute("roleOptions", roleOptions);
        model.addAttribute("matrixGroups", roleService.matrix(null).groups());
        // 选项值 → 权限码，供页面在选角色时即时预览「这个岗位能看到什么」（仅体验；真正的授权在服务端按角色解析）。
        java.util.Map<String, java.util.Collection<String>> rolePermissions = new java.util.LinkedHashMap<>();
        for (RoleOption o : roleOptions) {
            rolePermissions.put(o.value(), o.custom()
                    ? resolver.codesOfRoleId(o.roleId())
                    : resolver.codesOf(o.enumRole()));
        }
        model.addAttribute("rolePermissions", rolePermissions);
    }
}
