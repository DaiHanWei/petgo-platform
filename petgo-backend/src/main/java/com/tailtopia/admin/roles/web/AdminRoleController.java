package com.tailtopia.admin.roles.web;

import com.tailtopia.admin.roles.domain.AdminRoleEntity;
import com.tailtopia.admin.roles.dto.AdminRoleView;
import com.tailtopia.admin.roles.dto.RoleChange;
import com.tailtopia.admin.roles.service.AdminRoleService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.util.List;
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
 * 角色配置页（V1.3.0 Story 1.5，AB-21A，UI 稿 7-8 / 7-9 / 7-10）。<b>仅超管</b>（方法级 {@code hasRole('SUPER_ADMIN')}）。
 * SSR + PRG，与账号页同范式；矩阵整页（不进抽屉）。套模板 B 归 Story 6.5，本 story 只在现有样式上落功能。
 */
@Controller
public class AdminRoleController {

    private static final String SUPER = "hasRole('SUPER_ADMIN')";

    private final AdminRoleService roleService;
    private final Messages msg;

    public AdminRoleController(AdminRoleService roleService, Messages msg) {
        this.roleService = roleService;
        this.msg = msg;
    }

    @GetMapping("/admin/roles")
    @PreAuthorize(SUPER)
    public String list(Model model) {
        List<AdminRoleView> roles = roleService.list();
        model.addAttribute("active", "roles");
        model.addAttribute("roles", roles);
        model.addAttribute("systemCount", roles.stream().filter(AdminRoleView::system).count());
        model.addAttribute("customCount", roles.stream().filter(r -> !r.system()).count());
        return "admin/roles";
    }

    @GetMapping("/admin/roles/new")
    @PreAuthorize(SUPER)
    public String createForm(Model model) {
        model.addAttribute("active", "roles");
        model.addAttribute("role", null);
        model.addAttribute("accountCount", 0L);
        model.addAttribute("matrix", roleService.matrix(null));
        return "admin/roles-edit";
    }

    @GetMapping("/admin/roles/{id}/edit")
    @PreAuthorize(SUPER)
    public String editForm(@PathVariable long id, Model model) {
        AdminRoleEntity role = roleService.get(id);
        model.addAttribute("active", "roles");
        model.addAttribute("role", role);
        model.addAttribute("accountCount", roleService.accountCount(id));
        model.addAttribute("matrix", roleService.matrix(id));
        return "admin/roles-edit";
    }

    /** 新建自定义角色（name + permissionCodes[]）。 */
    @PostMapping("/admin/roles")
    @PreAuthorize(SUPER)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("name") String name,
            @RequestParam(value = "permissionCodes", required = false) List<String> permissionCodes,
            RedirectAttributes flash) {
        try {
            long id = roleService.create(name, permissionCodes, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.role.created", id, name == null ? "" : name.trim()));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/roles/new";
        }
        return "redirect:/admin/roles";
    }

    /** 编辑自定义角色：改名 + 权限同一事务（权限 diff 规则与预置角色相同，含 bump）；预置角色走 /permissions。 */
    @PostMapping("/admin/roles/{id}")
    @PreAuthorize(SUPER)
    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "permissionCodes", required = false) List<String> permissionCodes,
            RedirectAttributes flash) {
        try {
            RoleChange change = roleService.updateCustom(id, name, permissionCodes, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", noticeFor(change));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/roles/" + id + "/edit";
        }
        return "redirect:/admin/roles";
    }

    /** 预置角色只改权限（名称 / code 不可改：表单无该字段，服务层也不接）。 */
    @PostMapping("/admin/roles/{id}/permissions")
    @PreAuthorize(SUPER)
    public String updatePermissions(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "permissionCodes", required = false) List<String> permissionCodes,
            RedirectAttributes flash) {
        try {
            RoleChange change = roleService.updatePermissions(id, permissionCodes, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", noticeFor(change));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/roles/" + id + "/edit";
        }
        return "redirect:/admin/roles";
    }

    @PostMapping("/admin/roles/{id}/delete")
    @PreAuthorize(SUPER)
    public String delete(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            roleService.delete(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.role.deleted", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/roles";
    }

    private String noticeFor(RoleChange c) {
        if (c.noOp()) {
            return msg.get("admin.flash.role.unchanged", c.displayCode());
        }
        // D-2：重新登录后生效（本 story 靠 AD-1 版本号踢重登），文案不得写「立即生效」。
        return msg.get("admin.flash.role.updated", c.displayCode(), c.added(), c.removed(), c.affectedAccounts());
    }
}
