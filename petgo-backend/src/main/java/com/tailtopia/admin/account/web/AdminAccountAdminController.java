package com.tailtopia.admin.account.web;

import com.tailtopia.admin.account.dto.CreateAdminAccountForm;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.roles.dto.RoleOption;
import com.tailtopia.admin.roles.dto.RoleSelection;
import com.tailtopia.admin.roles.service.AdminRoleService;
import com.tailtopia.admin.roles.service.RolePermissionResolver;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.validation.Valid;
import com.tailtopia.admin.account.dto.AdminAccountView;
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
    public String accounts(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "create", required = false) String create, Model model) {
        populate(model);
        // V1.3.0 Story 1.2：自己那一行的「停用 / 改角色」渲染禁用态（体验；安全边界在服务层 self 护栏）。
        model.addAttribute("selfId", admin == null ? null : admin.getAdminAccountId());
        // ?create=1 深链自动开创建抽屉；整页校验失败的回显路径另行把 openCreate 置 true（见 create()）。
        model.addAttribute("openCreate", create != null);
        if (!model.containsAttribute("createAdminAccountForm")) {
            model.addAttribute("createAdminAccountForm", new CreateAdminAccountForm());
        }
        return "admin/admin-accounts";
    }

    // ===== V1.3.0 Story 6.5：模板 B 抽屉（只读 GET；写端点全部来自 Epic 1，路径 / 参数不变，只加 htmx 返回分支）=====

    /** 账号抽屉（B24）：基本信息 + 改名 / 换绑 / 改角色 / 改权限 / 停用 / 激活；非 htmx 访问回整页 {@code ?open=}。 */
    @GetMapping("/admin/accounts/{id}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/accounts?open=" + id;
        }
        populateDrawer(admin, id, model);
        return "admin/fragments/drawer-admin-account :: drawer";
    }

    /** 创建账号表单片段（页内 account-new 抽屉已预渲染同一片段；此端点供 htmx 重新拉取 / 深链）。 */
    @GetMapping("/admin/accounts/new/drawer")
    @PreAuthorize(CREATE_AUTH)
    public String createDrawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/accounts?create=1";
        }
        populateForms(model);
        model.addAttribute("createAdminAccountForm", new CreateAdminAccountForm());
        return "admin/fragments/drawer-admin-account :: createForm";
    }

    @PostMapping("/admin/accounts")
    @PreAuthorize(CREATE_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @Valid @ModelAttribute("createAdminAccountForm") CreateAdminAccountForm form,
            BindingResult binding, HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 6.5：htmx 提交——校验错误 422 回表单片段（带 errors）；业务错误 422 同片段带 createError；成功 HX-Redirect 到列表并开新账号抽屉
            if (binding.hasErrors()) {
                populateForms(model);
                response.setStatus(422);
                return "admin/fragments/drawer-admin-account :: createForm";
            }
            try {
                RoleSelection sel = roleService.resolveSelection(form.getRoleValue());
                long id = accountService.createAccount(form.getLarkEmail(), form.getDisplayName(),
                        sel, form.getPermissionCodes(), admin.getAdminAccountId());
                response.setHeader("HX-Redirect", "/admin/accounts?open=" + id);
                // ⚠️ htmx 收到 HX-Redirect 就直接跳转、**丢弃响应体** —— 这里回的是占位片段，不是 toast
                //    （回 toast 是死代码：它永远不会被渲染）。建成的反馈 = 跳回列表并自动开新账号抽屉。
                return "admin/fragments/drawer-admin-account :: created";
            } catch (AppException e) {
                populateForms(model);
                model.addAttribute("createError", msg.resolve(e));
                response.setStatus(422);
                return "admin/fragments/drawer-admin-account :: createForm";
            } catch (DataIntegrityViolationException e) {
                populateForms(model);
                model.addAttribute("createError", msg.get("admin.err.account.emailExists", form.getLarkEmail()));
                response.setStatus(422);
                return "admin/fragments/drawer-admin-account :: createForm";
            }
        }
        if (binding.hasErrors()) {
            populate(model);
            model.addAttribute("selfId", admin == null ? null : admin.getAdminAccountId());
            model.addAttribute("openCreate", true);
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
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            accountService.updatePermissions(id, permissionCodes, admin.getAdminAccountId());
            return afterAction(admin, id, msg.get("admin.flash.account.permsUpdated", id), model);
        }
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
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            RoleSelection sel = roleService.resolveSelection(roleValue);
            accountService.changeRole(id, sel, admin.getAdminAccountId());
            String label = sel.role() == com.tailtopia.admin.account.domain.AdminRole.ROLE_TEMPLATE ? sel.label() : msg.get(sel.role().titleCode());
            return afterAction(admin, id, msg.get("admin.flash.account.roleChanged", id, label), model);
        }
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
            @RequestParam("displayName") String displayName, HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            accountService.rename(id, displayName, admin.getAdminAccountId());
            return afterAction(admin, id, msg.get("admin.flash.account.renamed", id, displayName == null ? "" : displayName.trim()), model);
        }
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
            @RequestParam("newEmail") String newEmail, HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            try {
                accountService.rebindEmail(id, newEmail, admin.getAdminAccountId());
            } catch (DataIntegrityViolationException e) {
                throw AppException.validation("邮箱已被使用").code("admin.err.account.emailExists", newEmail == null ? "" : newEmail.trim());
            }
            return afterAction(admin, id, msg.get("admin.flash.account.emailRebound", id, newEmail == null ? "" : newEmail.trim()), model);
        }
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

    /**
     * 停用（V1.3.0 Story 2.3a 范式接入）：htmx 分支<b>不 try/catch</b>——AppException / 403 由
     * {@code AdminBusinessExceptionAdvice} 出 422 / 403 fragment；成功返回 toast fragment + {@code HX-Trigger} 刷新角标。
     * 整页分支维持 PRG + flash。真正套模板 B 在 Story 6.5。
     */
    @PostMapping("/admin/accounts/{id}/deactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, RedirectAttributes flash, HttpServletResponse response, Model model) {
        if (hx.isHtmx()) {
            accountService.deactivate(id, admin.getAdminAccountId());
            AdminFragmentResponses.triggerBadgeRefresh(response);
            // 6.5：抽屉内停用 → 抽屉重渲染 + oob 列表行 + toast（2.3a 起的 toast fragment 仍包含在内）
            return afterAction(admin, id, msg.get("admin.flash.account.deactivated", id), model);
        }
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
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            accountService.reactivate(id, admin.getAdminAccountId());
            return afterAction(admin, id, msg.get("admin.flash.account.reactivated", id), model);
        }
        try {
            accountService.reactivate(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.account.reactivated", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/accounts";
    }

    /** 抽屉模型：账号视图 + self / bootstrap 判定 + 角色下拉 / 矩阵（与整页同源）。 */
    private void populateDrawer(AdminUserDetails admin, long id, Model model) {
        // ⚠️ 不走 populate()：抽屉只需要这一个账号 + 角色下拉 / 权限矩阵，
        //    把全量账号连同逐账号权限解析再跑一遍是白花钱（抽屉每开一次、每处置一次都会走这里）。
        populateForms(model);
        AdminAccountView a = accountService.view(id);
        model.addAttribute("a", a);
        // Story 1.3：换绑禁用态只取决于这一个账号是不是 bootstrap 超管。
        model.addAttribute("bootstrapEmails", accountService.isBootstrapEmail(a.larkEmail())
                ? List.of(a.larkEmail()) : List.<String>of());
        model.addAttribute("summary", accountService.summary()); // afterAction 里摘要条随 oob 一并换
        model.addAttribute("selfId", admin == null ? null : admin.getAdminAccountId());
    }

    /** 抽屉内处置成功统一响应（Story 6.5 AC2）：抽屉体 oob + 列表行 oob + toast；失败由 AdminBusinessExceptionAdvice 出 422 / 403。 */
    private String afterAction(AdminUserDetails admin, long id, String toast, Model model) {
        populateDrawer(admin, id, model);
        model.addAttribute("toast", toast);
        return "admin/fragments/drawer-admin-account :: afterAction";
    }

    private void populate(Model model) {
        populateForms(model);
        var accounts = accountService.list();
        model.addAttribute("accounts", accounts);
        model.addAttribute("summary", accountService.summary()); // 6.5 摘要条
        // V1.3.0 Story 1.3：bootstrap 超管行「换绑邮箱」禁用态（判定逻辑与服务层护栏同源）。
        model.addAttribute("bootstrapEmails", accounts.stream()
                .map(v -> v.larkEmail()).filter(accountService::isBootstrapEmail).toList());
    }

    /** 角色下拉 / 权限矩阵 / 角色权限预览 —— 建号表单与抽屉都要，与「全量账号列表」无关。 */
    private void populateForms(Model model) {
        model.addAttribute("active", "accounts");
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
