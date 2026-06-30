package com.tailtopia.admin.usermgmt.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台用户搜索与详情（Story 3.1，AB-UA-01）。**纯只读 GET**，SSR + HTMX，不返 JSON、不写审计。
 * 门控 {@code @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('user.view')")}。
 */
@Controller
public class AdminUserController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('user.view')";
    private static final String DEACTIVATE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('user.deactivate')";
    private static final String DELETE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('user.delete')";

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/admin/users")
    @PreAuthorize(AUTH)
    public String users(@RequestParam(value = "q", required = false) String q,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "users");
        model.addAttribute("q", q);
        model.addAttribute("searched", q != null && !q.isBlank());
        model.addAttribute("results", adminUserService.search(q));
        return hxRequest != null ? "admin/users :: rows" : "admin/users";
    }

    @GetMapping("/admin/users/{userId}")
    @PreAuthorize(AUTH)
    public String userDetail(@PathVariable long userId, Model model) {
        model.addAttribute("active", "users");
        model.addAttribute("user", adminUserService.detail(userId));
        return "admin/user-detail";
    }

    @PostMapping("/admin/users/{userId}/deactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam("reason") String reason, RedirectAttributes flash) {
        try {
            adminUserService.deactivate(userId, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已停用该用户（即时不可登录，进行中问诊已强关）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/admin/users/{userId}/reactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String reactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            RedirectAttributes flash) {
        adminUserService.reactivate(userId, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已重新激活该用户");
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/admin/users/{userId}/delete")
    @PreAuthorize(DELETE_AUTH)
    public String delete(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam("type") String type, @RequestParam("note") String note,
            RedirectAttributes flash) {
        try {
            adminUserService.deleteUser(userId,
                    com.tailtopia.admin.usermgmt.domain.DeletionType.fromOrNull(type), note,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已提交删除（不可逆，级联匿名化处理中）");
            return "redirect:/admin/users";
        } catch (com.tailtopia.shared.error.AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/users/" + userId;
        }
    }
}
